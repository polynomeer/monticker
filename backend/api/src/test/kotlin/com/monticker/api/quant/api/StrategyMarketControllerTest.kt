package com.monticker.api.quant.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.domain.RuleSetStatus
import com.monticker.api.quant.infrastructure.MARKET_VISIBLE_FROM
import com.monticker.api.quant.infrastructure.RuleSetNameView
import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.settlement.creator.application.CreatorEarningsService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Optional

class StrategyMarketControllerTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val jwtTokenProvider = mockk<JwtTokenProvider>()
    private val creatorEarningsService = mockk<CreatorEarningsService>()
    private val ruleSetRepository = mockk<RuleSetRepository>()
    private val performanceQuery = mockk<com.monticker.api.quant.application.StrategyPerformanceQuery>()
    private val searchRepository = mockk<com.monticker.api.quant.infrastructure.StrategyMarketSearchRepository>()
    private val controller = StrategyMarketController(jdbc, jwtTokenProvider, creatorEarningsService, ruleSetRepository, performanceQuery, searchRepository)

    private fun doc(userId: Long, status: String) =
        RuleSetDocument(id = "rs1", userId = userId, name = "test", status = status)

    // 다른 사용자의 룰셋 ID를 알아내는 것만으로 마켓에 공유해버릴 수 있었던 broken object-level
    // authorization 회귀 방지.

    @Test
    fun `share rejects a ruleset the caller does not own`() {
        every { jwtTokenProvider.getUserId("token") } returns 1L
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.empty()

        // GlobalExceptionHandler가 실제 HTTP 요청 처리 중에만 개입하므로, 컨트롤러를 직접
        // 호출하는 이 테스트에서는 404로 변환되기 전의 원본 예외를 검증한다.
        assertThrows<NoSuchElementException> {
            controller.share("Bearer token", StrategyShareRequest(rulesetId = "rs1"))
        }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), any<Class<Long>>(), *anyVararg()) }
    }

    @Test
    fun `share rejects a ruleset that has not been backtested yet`() {
        every { jwtTokenProvider.getUserId("token") } returns 1L
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(doc(1L, RuleSetStatus.DRAFT.name))

        assertThrows<IllegalArgumentException> {
            controller.share("Bearer token", StrategyShareRequest(rulesetId = "rs1"))
        }
    }

    @Test
    fun `share succeeds for a backtested ruleset owned by the caller`() {
        every { jwtTokenProvider.getUserId("token") } returns 1L
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(doc(1L, RuleSetStatus.BACKTESTED.name))
        every {
            jdbc.queryForObject(any<String>(), Long::class.java, "rs1", 1L, "설명", BigDecimal.ZERO)
        } returns 42L

        val response = controller.share("Bearer token", StrategyShareRequest(rulesetId = "rs1", description = "설명"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo(mapOf("id" to 42L, "rulesetId" to "rs1", "price" to BigDecimal.ZERO))
    }

    @Test
    fun `share succeeds for a ruleset currently running a forward test`() {
        every { jwtTokenProvider.getUserId("token") } returns 1L
        every { ruleSetRepository.findByIdAndUserId("rs1", 1L) } returns Optional.of(doc(1L, RuleSetStatus.RUNNING.name))
        every {
            jdbc.queryForObject(any<String>(), Long::class.java, "rs1", 1L, null, BigDecimal.ZERO)
        } returns 7L

        val response = controller.share("Bearer token", StrategyShareRequest(rulesetId = "rs1"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    // ── list — ADR-035: price 노출, 로그인 사용자 기준 isSubscribed ──────────────────

    private fun stubListRows(marketId: Long = 1L) {
        every { jdbc.queryForList(match<String> { it.contains("FROM strategy_market") }, any<Int>(), any<Int>()) } returns listOf(
            linkedMapOf<String, Any?>("id" to marketId, "ruleset_id" to "rs1", "description" to null, "price" to BigDecimal("5000"), "subscribe_count" to 3, "created_at" to null, "author_nickname" to "작성자")
        )
        every { ruleSetRepository.findAllById(listOf("rs1")) } returns listOf(doc(1L, RuleSetStatus.BACKTESTED.name))
        every { performanceQuery.summarize(listOf("rs1")) } returns mapOf(
            "rs1" to com.monticker.api.quant.application.StrategyPerformance(backtest = null, forward = null),
        )
    }

    @Test
    fun `list carries the performance summary for each shared strategy`() {
        stubListRows()

        val row = controller.list(auth = null, page = 0, size = 20).body!!.first()

        assertThat(row["performance"]).isEqualTo(com.monticker.api.quant.application.StrategyPerformance(null, null))
        assertThat(row).doesNotContainKey("ruleDefinition")
    }

    @Test
    fun `list은 로그인 없이도 조회되고 isSubscribed는 false다`() {
        stubListRows()

        val response = controller.list(auth = null, page = 0, size = 20)

        val row = response.body!!.first()
        assertThat(row["price"]).isEqualTo(BigDecimal("5000"))
        assertThat(row["isSubscribed"]).isEqualTo(false)
    }

    @Test
    fun `list은 로그인한 사용자가 구독 중인 항목을 isSubscribed=true로 표시한다`() {
        stubListRows(marketId = 1L)
        every { jwtTokenProvider.getUserId("token") } returns 9L
        every { jdbc.queryForList("SELECT market_id FROM strategy_subscriptions WHERE user_id = ?", Long::class.java, 9L) } returns listOf(1L)

        val response = controller.list(auth = "Bearer token", page = 0, size = 20)

        assertThat(response.body!!.first()["isSubscribed"]).isEqualTo(true)
    }

    // ── ADR-080 유료 전략 구독은 서버에서 닫혀 있다 ───────────────────────────────

    @Test
    fun `subscribe to a paid strategy is refused before any subscription row or payment`() {
        every { jwtTokenProvider.getUserId("token") } returns 9L
        every { jdbc.queryForMap(match<String> { it.contains("FROM strategy_market WHERE id = ?") }, 1L) } returns
            mapOf("creator_id" to 1L, "price" to BigDecimal("5000"), "ruleset_id" to "rs1")

        assertThrows<com.monticker.api.common.exception.BusinessRuleException> { controller.subscribe("Bearer token", 1L) }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
        verify(exactly = 0) { creatorEarningsService.onStrategySubscribed(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `share rejects a negative or fractional price`() {
        every { jwtTokenProvider.getUserId("token") } returns 1L

        assertThrows<IllegalArgumentException> { controller.share("Bearer token", StrategyShareRequest(rulesetId = "rs1", price = BigDecimal("-1"))) }
        assertThrows<IllegalArgumentException> { controller.share("Bearer token", StrategyShareRequest(rulesetId = "rs1", price = BigDecimal("100.5"))) }
    }

    // 보안 리뷰 — 마켓 목록은 비로그인에도 열려 있다. 작성자 이메일(로그인 ID)을 내보내지 않고 닉네임만 싣는다.
    @Test
    fun `list exposes the author nickname, never the email`() {
        stubListRows()

        controller.list(auth = null, page = 0, size = 20)

        verify {
            jdbc.queryForList(match<String> { it.contains("FROM strategy_market") && !it.contains("email") && it.contains("u.nickname AS author_nickname") }, any<Int>(), any<Int>())
        }
    }

    // 보안 리뷰 — 없는 마켓 id 구독은 EmptyResultDataAccessException → 500이었다. 404(NoSuchElementException)로 답한다.
    @Test
    fun `subscribe to an unknown market id is not found`() {
        every { jwtTokenProvider.getUserId("token") } returns 9L
        every { jdbc.queryForMap(match<String> { it.contains("FROM strategy_market WHERE id = ?") }, 404L) } throws
            org.springframework.dao.EmptyResultDataAccessException(1)

        assertThrows<NoSuchElementException> { controller.subscribe("Bearer token", 404L) }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    // ── count — 목록과 같은 공개 범위만 센다 ─────────────────────────────────────

    @Test
    fun `count returns the total of strategies visible in the market list`() {
        val sql = slot<String>()
        every { jdbc.queryForObject(capture(sql), Long::class.java) } returns 37L

        val response = controller.count()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo(mapOf("total" to 37L))
        // 목록과 같은 FROM·JOIN을 써야 작성자가 사라진 전략처럼 목록에 안 보이는 행이 총수에 섞이지 않는다.
        assertThat(sql.captured).startsWith("SELECT COUNT(*)").contains(MARKET_VISIBLE_FROM)
    }

    @Test
    fun `list and count share the same visibility clause`() {
        val listSql = slot<String>()
        every { jdbc.queryForList(capture(listSql), any<Int>(), any<Int>()) } returns emptyList()
        every { ruleSetRepository.findAllById(emptyList()) } returns emptyList()
        every { performanceQuery.summarize(emptyList()) } returns emptyMap()

        controller.list(auth = null, page = 0, size = 20)

        assertThat(listSql.captured).contains(MARKET_VISIBLE_FROM)
    }

    @Test
    fun `count falls back to zero when the query yields null`() {
        every { jdbc.queryForObject(any<String>(), Long::class.java) } returns null

        assertThat(controller.count().body).isEqualTo(mapOf("total" to 0L))
    }

    // ── search — 서버 측 전략 검색 ────────────────────────────────────────────────

    private fun nameView(id: String, name: String) = RuleSetNameView(id, name)

    @Test
    fun `search rejects blank, too long, oversized and too deep queries before touching storage`() {
        val bad = listOf(
            Triple("   ", 0, 20),
            Triple("x".repeat(StrategyMarketController.MAX_SEARCH_QUERY_LENGTH + 1), 0, 20),
            Triple("a", 0, 0),
            Triple("a", 0, StrategyMarketController.MAX_SEARCH_SIZE + 1),
            Triple("a", -1, 20),
            Triple("a", StrategyMarketController.MAX_SEARCH_OFFSET / 20 + 1, 20),
            Triple("a", Int.MAX_VALUE, 50),
        )
        bad.forEach { (q, page, size) ->
            assertThrows<IllegalArgumentException>("q=$q page=$page size=$size") { controller.search(null, q, page, size) }
        }
        verify(exactly = 0) { searchRepository.visibleRulesetIds(any()) }
        verify(exactly = 0) { searchRepository.search(any(), any(), any(), any()) }
    }

    @Test
    fun `search matches names among visible strategies only and pages the result`() {
        every { searchRepository.visibleRulesetIds(any()) } returns listOf("rs1", "rs2")
        // rs1만 이름이 맞는다(대소문자 무시). 공개 목록에 없는 룰셋은 애초에 후보로 조회하지 않는다.
        every { ruleSetRepository.findByIdIn(listOf("rs1", "rs2")) } returns listOf(nameView("rs1", "Momentum Alpha"), nameView("rs2", "가치투자"))
        val row = mapOf<String, Any?>("id" to 5L, "ruleset_id" to "rs1", "description" to null, "price" to BigDecimal.ZERO,
            "subscribe_count" to 3, "created_at" to null, "author_nickname" to "kim")
        every { searchRepository.search("moment", listOf("rs1"), 20, 20) } returns listOf(row)
        every { searchRepository.count("moment", listOf("rs1")) } returns 21L
        every { ruleSetRepository.findAllById(listOf("rs1")) } returns listOf(RuleSetDocument(id = "rs1", userId = 9L, name = "Momentum Alpha"))
        every { performanceQuery.summarize(listOf("rs1")) } returns emptyMap()

        val body = controller.search(null, "  moment ", page = 1, size = 20).body!!

        @Suppress("UNCHECKED_CAST")
        val items = body["items"] as List<Map<String, Any?>>
        assertThat(items.single()["name"]).isEqualTo("Momentum Alpha")
        assertThat(items.single()["isSubscribed"]).isEqualTo(false)
        assertThat(body["total"]).isEqualTo(21L)
        assertThat(body["hasMore"]).isEqualTo(false)   // 20 + 1 = 21
    }

    @Test
    fun `search skips the name lookup when no strategy is visible`() {
        every { searchRepository.visibleRulesetIds(any()) } returns emptyList()
        every { searchRepository.search("a", emptyList(), 20, 0) } returns emptyList()
        every { searchRepository.count("a", emptyList()) } returns 0L
        every { ruleSetRepository.findAllById(emptyList()) } returns emptyList()
        every { performanceQuery.summarize(emptyList()) } returns emptyMap()

        val body = controller.search(null, "a", 0, 20).body!!

        assertThat(body["total"]).isEqualTo(0L)
        verify(exactly = 0) { ruleSetRepository.findByIdIn(any()) }
    }
}
