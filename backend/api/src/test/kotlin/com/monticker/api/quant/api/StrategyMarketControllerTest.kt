package com.monticker.api.quant.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.domain.RuleSetStatus
import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.settlement.creator.application.CreatorEarningsService
import io.mockk.every
import io.mockk.mockk
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
    private val controller = StrategyMarketController(jdbc, jwtTokenProvider, creatorEarningsService, ruleSetRepository)

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
            linkedMapOf<String, Any?>("id" to marketId, "ruleset_id" to "rs1", "description" to null, "price" to BigDecimal("5000"), "subscribe_count" to 3, "created_at" to null, "author_email" to "a@b.com")
        )
        every { ruleSetRepository.findAllById(listOf("rs1")) } returns listOf(doc(1L, RuleSetStatus.BACKTESTED.name))
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
}
