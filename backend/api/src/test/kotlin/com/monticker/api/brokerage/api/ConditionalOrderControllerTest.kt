package com.monticker.api.brokerage.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.application.ConditionalOrderInsights
import com.monticker.api.brokerage.application.ConditionalOrderQuote
import com.monticker.api.brokerage.application.ConditionalOrderService
import com.monticker.api.brokerage.application.ConditionalOrderStats
import com.monticker.api.brokerage.application.ConditionalTriggerPage
import com.monticker.api.brokerage.domain.ConditionalOrder
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.common.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.Instant

/** 유효 기간(validDays) 경계 검증과 통계·발동 기록·시세 조회가 인증 주체로만 걸러지는지. */
class ConditionalOrderControllerTest {
    private val service = mockk<ConditionalOrderService>()
    private val brokerageService = mockk<BrokerageService>()
    private val jwt = mockk<JwtTokenProvider> { every { getUserId("t") } returns 5L }
    private val insights = mockk<ConditionalOrderInsights>()
    private val mvc = MockMvcBuilders.standaloneSetup(ConditionalOrderController(service, brokerageService, jwt, insights))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    init {
        justRun { brokerageService.requireCurrentConsents(5L) }
    }

    private fun order(expires: Instant) = ConditionalOrder(
        id = 1L, userId = 5L, accountId = 1L, stockId = 9L, symbol = "005930", side = OrderSide.SELL,
        triggerType = ConditionalTriggerType.STOP_LOSS, triggerPrice = BigDecimal("70000"), orderType = OrderType.MARKET,
        quantity = 1, expiresAt = expires,
    )

    private fun single(validDays: String?) = """
        {"symbol":"005930","side":"SELL","quantity":1,
         "leg":{"triggerType":"STOP_LOSS","triggerPrice":70000,"orderType":"MARKET"}
         ${validDays?.let { ""","validDays":$it""" } ?: ""}}
    """.trimIndent()

    private fun oco(validDays: String) = """
        {"symbol":"005930","side":"SELL","quantity":1,"validDays":$validDays,
         "legs":[{"triggerType":"STOP_LOSS","triggerPrice":70000,"orderType":"MARKET"},
                 {"triggerType":"TAKE_PROFIT","triggerPrice":80000,"orderType":"MARKET"}]}
    """.trimIndent()

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "91", "365"])
    fun `validDays outside 1 to 90 is 400 before the service is called`(days: String) {
        mvc.perform(post("/api/brokerage/conditional-orders").header("Authorization", "Bearer t")
            .contentType(MediaType.APPLICATION_JSON).content(single(days)))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/brokerage/conditional-orders/oco").header("Authorization", "Bearer t")
            .contentType(MediaType.APPLICATION_JSON).content(oco(days)))
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { service.create(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { service.createOco(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `validDays is passed through and omitted means default`() {
        every { service.create(5L, "005930", OrderSide.SELL, 1, any(), any()) } returns order(Instant.parse("2026-10-15T15:00:00Z"))

        mvc.perform(post("/api/brokerage/conditional-orders").header("Authorization", "Bearer t")
            .contentType(MediaType.APPLICATION_JSON).content(single("7")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stockId").value(9))
            .andExpect(jsonPath("$.expiresAt").exists())
        verify { service.create(5L, "005930", OrderSide.SELL, 1, any(), 7) }

        mvc.perform(post("/api/brokerage/conditional-orders").header("Authorization", "Bearer t")
            .contentType(MediaType.APPLICATION_JSON).content(single(null)))
            .andExpect(status().isOk)
        verify { service.create(5L, "005930", OrderSide.SELL, 1, any(), null) }
    }

    @Test
    fun `stats triggers and quotes are scoped to the token user`() {
        every { insights.stats(5L, any()) } returns ConditionalOrderStats(mapOf("ACTIVE" to 2L), 2L, 1L, Instant.EPOCH)
        every { insights.triggers(5L, 1, 10) } returns ConditionalTriggerPage(emptyList(), 1, 10, 0, 0)
        every { insights.quotes(5L) } returns listOf(ConditionalOrderQuote(9L, "005930", "삼성전자", BigDecimal("70100"), Instant.EPOCH))

        mvc.perform(get("/api/brokerage/conditional-orders/stats").header("Authorization", "Bearer t"))
            .andExpect(status().isOk).andExpect(jsonPath("$.byStatus.ACTIVE").value(2)).andExpect(jsonPath("$.firedThisMonth").value(1))
        mvc.perform(get("/api/brokerage/conditional-orders/triggers?page=1&size=10").header("Authorization", "Bearer t"))
            .andExpect(status().isOk).andExpect(jsonPath("$.page").value(1))
        mvc.perform(get("/api/brokerage/conditional-orders/quotes").header("Authorization", "Bearer t"))
            .andExpect(status().isOk).andExpect(jsonPath("$[0].symbol").value("005930"))

        verify { insights.stats(5L, any()) }
        verify { insights.triggers(5L, 1, 10) }
        verify { insights.quotes(5L) }
    }

    @Test
    fun `cancelling someone else's order is the same 404 as a missing one`() {
        every { service.cancel(5L, 77L) } throws NoSuchElementException("조건부 주문 없음: 77")
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/brokerage/conditional-orders/77")
            .header("Authorization", "Bearer t"))
            .andExpect(status().isNotFound)
    }
}
