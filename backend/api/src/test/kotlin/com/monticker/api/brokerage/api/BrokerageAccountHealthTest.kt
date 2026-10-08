package com.monticker.api.brokerage.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.infrastructure.BrokerCallHealthTracker
import com.monticker.api.brokerage.infrastructure.BrokerCallOperation
import com.monticker.api.brokerage.infrastructure.BrokerErrorCode
import com.monticker.api.common.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration

/** GET /api/brokerage/account — 마지막 증권사 호출 지연·오류 코드. 키·원문 메시지는 내보내지 않는다. */
class BrokerageAccountHealthTest {
    private val brokerageService = mockk<BrokerageService>()
    private val jwt = mockk<JwtTokenProvider> { every { getUserId("t") } returns 5L }
    private val tracker = BrokerCallHealthTracker()
    private val mvc = MockMvcBuilders.standaloneSetup(BrokerageController(brokerageService, jwt, mockk(), tracker))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val account = BrokerageAccount(id = 3L, userId = 5L, provider = BrokerageProvider.KIS, accountNumber = "12345678-01").apply {
        appKey = "secret-app-key"; appSecret = "secret-app-secret"; accessToken = "secret-token"
    }

    @Test
    fun `no observation yet means no apiHealth`() {
        every { brokerageService.getAccount(5L) } returns account
        mvc.perform(get("/api/brokerage/account").header("Authorization", "Bearer t"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.apiHealth").doesNotExist())
    }

    @Test
    fun `reports last latency and last error as a fixed code`() {
        every { brokerageService.getAccount(5L) } returns account
        val key = BrokerCallHealthTracker.keyOf(BrokerageProvider.KIS, "secret-app-key", "12345678-01")
        tracker.record(key, BrokerCallOperation.GET_BALANCE, Duration.ofMillis(900), BrokerErrorCode.TIMEOUT)
        tracker.record(key, BrokerCallOperation.GET_ORDER_STATUS, Duration.ofMillis(120), null)

        mvc.perform(get("/api/brokerage/account").header("Authorization", "Bearer t"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.apiHealth.lastLatencyMs").value(120))
            .andExpect(jsonPath("$.apiHealth.lastOperation").value("GET_ORDER_STATUS"))
            .andExpect(jsonPath("$.apiHealth.lastErrorCode").value("TIMEOUT"))
            .andExpect(jsonPath("$.apiHealth.lastErrorOperation").value("GET_BALANCE"))
            .andExpect(content().string(not(containsString("secret"))))
    }
}
