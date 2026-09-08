package com.monticker.api.brokerage.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.domain.RebalanceTarget
import com.monticker.api.brokerage.domain.RebalanceTargetSource
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.RebalanceTargetRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Optional

class RebalanceTargetServiceTest {

    private val accountRepo = mockk<BrokerageAccountRepository>()
    private val targetRepo = mockk<RebalanceTargetRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val objectMapper = ObjectMapper()
    private val service = RebalanceTargetService(accountRepo, targetRepo, jdbc, objectMapper)

    private fun makeAccount() = BrokerageAccount(id = 1L, userId = 1L, provider = BrokerageProvider.MOCK, accountNumber = "12345678")

    private fun stubStock(symbol: String, id: Long = 1L) {
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, symbol) } returns id
    }

    @Test
    fun `연동된 계좌가 없으면 저장할 수 없다`() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.empty()
        stubStock("005930")

        assertThatThrownBy {
            service.save(1L, mapOf("005930" to BigDecimal("0.5")), BigDecimal("5.00"), RebalanceTargetSource.MANUAL)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `비중의 합이 100퍼센트를 넘으면 저장할 수 없다`() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(makeAccount())
        stubStock("005930")
        stubStock("000660", 2L)

        assertThatThrownBy {
            service.save(1L, mapOf("005930" to BigDecimal("0.6"), "000660" to BigDecimal("0.6")), BigDecimal("5.00"), RebalanceTargetSource.MANUAL)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `존재하지 않는 종목이 포함되면 저장할 수 없다`() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(makeAccount())
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, "999999") } throws IllegalStateException("not found")

        assertThatThrownBy {
            service.save(1L, mapOf("999999" to BigDecimal("0.5")), BigDecimal("5.00"), RebalanceTargetSource.MANUAL)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `정상 저장하면 계좌에 연결된 목표가 만들어진다`() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(makeAccount())
        stubStock("005930")
        val savedSlot = slot<RebalanceTarget>()
        every { targetRepo.findByAccountId(1L) } returns Optional.empty()
        every { targetRepo.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.save(1L, mapOf("005930" to BigDecimal("0.5")), BigDecimal("5.00"), RebalanceTargetSource.MANUAL)

        assertThat(result.accountId).isEqualTo(1L)
        assertThat(service.parseWeights(result)).containsEntry("005930", BigDecimal("0.5"))
    }

    @Test
    fun `같은 계좌에 다시 저장하면 기존 행을 갱신한다`() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(makeAccount())
        stubStock("000660", 2L)
        val existing = RebalanceTarget(
            id = 10L, userId = 1L, accountId = 1L,
            weightsJson = objectMapper.writeValueAsString(mapOf("005930" to BigDecimal("0.5"))),
            thresholdPct = BigDecimal("5.00"), source = RebalanceTargetSource.MANUAL,
        )
        every { targetRepo.findByAccountId(1L) } returns Optional.of(existing)
        every { targetRepo.save(any<RebalanceTarget>()) } answers { firstArg() }

        val result = service.save(1L, mapOf("000660" to BigDecimal("0.3")), BigDecimal("10.00"), RebalanceTargetSource.OPTIMIZER)

        assertThat(result.id).isEqualTo(10L)
        assertThat(service.parseWeights(result)).containsEntry("000660", BigDecimal("0.3"))
        assertThat(result.thresholdPct).isEqualByComparingTo(BigDecimal("10.00"))
    }
}
