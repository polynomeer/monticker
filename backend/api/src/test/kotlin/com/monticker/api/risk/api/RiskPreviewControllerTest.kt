package com.monticker.api.risk.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.common.exception.GlobalExceptionHandler
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import java.math.BigDecimal

/** ADR-092 — POST /api/risk/preview의 입력 검증·호출 제한·주문 불가. */
class RiskPreviewControllerTest {
    private val userId = 7L
    private val riskChecker = mockk<RiskCheckerService>()
    private val mvc = MockMvcBuilders.standaloneSetup(RiskPreviewController(riskChecker))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())
        every { riskChecker.preview(any(), any(), any(), any(), any()) } returns
            RiskCheckResult(approved = true, blockedBy = null, severity = "APPROVED", checks = emptyList())
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    private fun postJson(body: String) =
        mvc.perform(post("/api/risk/preview").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `previews for the authenticated user only`() {
        postJson("""{"stockId":1,"side":"BUY","quantity":3,"estimatedPrice":1000,"userId":999}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approved").value(true))
        verify { riskChecker.preview(userId, 1L, "BUY", 3, BigDecimal("1000")) }
    }

    @Test
    fun `missing price means market order`() {
        postJson("""{"stockId":1,"side":"SELL","quantity":2}""").andExpect(status().isOk)
        verify { riskChecker.preview(userId, 1L, "SELL", 2, BigDecimal.ZERO) }
    }

    @Test
    fun `an integral decimal quantity is accepted`() {
        postJson("""{"stockId":1,"side":"BUY","quantity":3.0,"estimatedPrice":1000}""").andExpect(status().isOk)
        verify { riskChecker.preview(userId, 1L, "BUY", 3, BigDecimal("1000")) }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"stockId":1,"side":"buy","quantity":1}""",
        """{"stockId":1,"side":"HOLD","quantity":1}""",
        """{"stockId":1,"quantity":1}""",
        """{"stockId":1,"side":"BUY","quantity":0}""",
        """{"stockId":1,"side":"BUY","quantity":-2}""",
        """{"stockId":1,"side":"BUY","quantity":1.5}""",
        """{"stockId":1,"side":"BUY"}""",
        """{"stockId":1,"side":"BUY","quantity":1000001}""",
        """{"stockId":1,"side":"BUY","quantity":1,"estimatedPrice":-1}""",
        """{"stockId":1,"side":"BUY","quantity":1,"estimatedPrice":1e13}""",
        """{"side":"BUY","quantity":1}""",
        """{"stockId":0,"side":"BUY","quantity":1}""",
    ])
    fun `rejects invalid input with 400 before judging`(body: String) {
        postJson(body).andExpect(status().isBadRequest)
        verify(exactly = 0) { riskChecker.preview(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown stock is 404`() {
        every { riskChecker.preview(any(), 404L, any(), any(), any()) } throws NoSuchElementException("종목을 찾을 수 없습니다: 404")
        postJson("""{"stockId":404,"side":"BUY","quantity":1}""").andExpect(status().isNotFound)
    }

    @Test
    fun `is rate limited per user at 60 per minute under its own key`() {
        val m = RiskPreviewController::class.java.methods.first { it.name == "preview" }
        val rl = m.getAnnotation(RateLimited::class.java)
        assertThat(rl).isNotNull()
        assertThat(rl.limit).isEqualTo(60)
        assertThat(rl.windowSec).isEqualTo(60)
        // 감사되는 사전 점검(risk.dryrun)·주문(matching.order)과 한도를 나눠 쓰지 않는다
        assertThat(rl.keyPrefix).isEqualTo("risk.preview")
    }

    @Test
    fun `exposes nothing but the preview`() {
        val mappings = RiskPreviewController::class.java.declaredMethods.filter {
            it.isAnnotationPresent(GetMapping::class.java) || it.isAnnotationPresent(PutMapping::class.java) ||
                it.isAnnotationPresent(DeleteMapping::class.java) ||
                it.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping::class.java)
        }
        assertThat(mappings.map { it.name }).containsExactly("preview")
    }
}
