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
}
