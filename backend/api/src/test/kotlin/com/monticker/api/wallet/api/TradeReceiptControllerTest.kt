package com.monticker.api.wallet.api

import com.monticker.api.common.exception.GlobalExceptionHandler
import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.paper.application.PaperTradeSummary
import com.monticker.api.wallet.application.EmotionTagService
import com.monticker.api.wallet.application.ReceiptService
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal

/**
 * GET /api/paper/trades/{id}/receipt 의 소유권 검사 — 실제 ReceiptService와
 * GlobalExceptionHandler를 거쳐 HTTP 상태까지 확인한다. 남의 거래가 400, 없는 거래가 404로 갈리면
 * 거래 id 존재 여부를 열거할 수 있으므로 둘은 구분되지 않아야 한다.
 */
class TradeReceiptControllerTest {

    private val tradeQueryService = mockk<PaperTradeQueryService>()
    private val ledgerRepo = mockk<LedgerEventRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val receiptService = ReceiptService(tradeQueryService, ledgerRepo, jdbc)
    private val controller = TradeReceiptController(receiptService, mockk<EmotionTagService>())

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val owner = 1L
    private val attacker = 2L
    private val tradeId = 10L

    private fun loginAs(userId: Long) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
    }

    @BeforeEach
    fun setUp() {
        every { tradeQueryService.findById(tradeId) } returns PaperTradeSummary(
            id = tradeId, userId = owner, stockId = 100L, side = "BUY",
            quantity = 1, price = BigDecimal("100000"), amount = BigDecimal("100000"),
        )
        every { tradeQueryService.findById(404L) } returns null
        every { jdbc.queryForMap(any<String>(), eq(100L)) } returns mapOf("symbol" to "005930", "name" to "삼성전자")
        every { ledgerRepo.findTopByPaperTradeIdOrderByIdDesc(tradeId) } returns null
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `owner can read their own receipt`() {
        loginAs(owner)

        mockMvc.perform(get("/api/paper/trades/$tradeId/receipt"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tradeId").value(tradeId))
            .andExpect(jsonPath("$.stockSymbol").value("005930"))
    }

    @Test
    fun `another user gets 404 reading the owner's receipt`() {
        loginAs(attacker)

        mockMvc.perform(get("/api/paper/trades/$tradeId/receipt"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.stockSymbol").doesNotExist())

        verify(exactly = 0) { jdbc.queryForMap(any<String>(), *anyVararg()) }
        verify(exactly = 0) { ledgerRepo.findTopByPaperTradeIdOrderByIdDesc(any()) }
    }

    @Test
    fun `another user's trade is indistinguishable from a missing trade`() {
        loginAs(attacker)

        val notOwned = mockMvc.perform(get("/api/paper/trades/$tradeId/receipt"))
            .andExpect(status().isNotFound).andReturn().response.contentAsString
        val missing = mockMvc.perform(get("/api/paper/trades/404/receipt"))
            .andExpect(status().isNotFound).andReturn().response.contentAsString

        // timestamp와 요청한 id만 다르고 나머지(상태·메시지 형태)는 같아야 한다
        fun normalize(body: String) = body
            .replace(Regex("\"timestamp\":[^,}]*"), "")
            .replace("trade not found: $tradeId", "trade not found: ID")
            .replace("trade not found: 404", "trade not found: ID")
        assertThat(normalize(notOwned)).isEqualTo(normalize(missing))
    }
}
