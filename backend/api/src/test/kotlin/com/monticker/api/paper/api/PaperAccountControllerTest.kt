package com.monticker.api.paper.api

import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.common.exception.GlobalExceptionHandler
import com.monticker.api.paper.application.PaperAccountResponse
import com.monticker.api.paper.application.PaperAccountService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal

/** ADR-089 — POST/GET /api/paper/account. 사용자는 인증 주체에서만, 상태 코드는 201/200/400/409. */
class PaperAccountControllerTest {
    private val accountService = mockk<PaperAccountService>()
    private val controller = PaperController(mockk(), mockk(), mockk(), accountService)
    private val mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(GlobalExceptionHandler()).build()

    @BeforeEach
    fun auth() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(42L, null, emptyList())
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun open(body: String) = mvc.perform(post("/api/paper/account").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `first creation returns 201 for the authenticated user`() {
        every { accountService.open(42L, BigDecimal("30000000")) } returns
            PaperAccountResponse(BigDecimal("30000000"), BigDecimal("30000000"), created = true)

        open("""{"initialCapital":30000000}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.initialCapital").value(30000000))
            .andExpect(jsonPath("$.created").value(true))
    }

    @Test
    fun `repeat with the same capital returns 200`() {
        every { accountService.open(42L, any()) } returns PaperAccountResponse(BigDecimal("10000000"), BigDecimal("9000000"), created = false)

        open("""{"initialCapital":10000000}""").andExpect(status().isOk).andExpect(jsonPath("$.created").value(false))
    }

    @Test
    fun `a userId in the body is ignored — ownership comes from the token only`() {
        every { accountService.open(42L, any()) } returns PaperAccountResponse(BigDecimal("10000000"), BigDecimal("10000000"), created = true)

        open("""{"initialCapital":10000000,"userId":1}""").andExpect(status().isCreated)

        verify(exactly = 1) { accountService.open(42L, any()) }
        verify(exactly = 0) { accountService.open(1L, any()) }
    }

    @Test
    fun `non-whitelisted capital is 400`() {
        every { accountService.open(42L, any()) } throws IllegalArgumentException("initialCapital은 10000000, 30000000, 100000000 중 하나여야 합니다")

        open("""{"initialCapital":50000000}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `non-numeric capital is 400 before reaching the service`() {
        open("""{"initialCapital":"lots"}""").andExpect(status().isBadRequest)
        verify(exactly = 0) { accountService.open(any(), any()) }
    }

    @Test
    fun `existing account with a different capital is 409`() {
        every { accountService.open(42L, any()) } throws BusinessRuleException("이미 시작 자금 10,000,000원으로 만든 모의 계좌가 있습니다.")

        open("""{"initialCapital":100000000}""").andExpect(status().isConflict)
    }

    @Test
    fun `GET account is 204 when none exists and 200 otherwise`() {
        every { accountService.find(42L) } returns null
        mvc.perform(get("/api/paper/account")).andExpect(status().isNoContent)

        every { accountService.find(42L) } returns PaperAccountResponse(BigDecimal("100000000"), BigDecimal("1"), created = false)
        mvc.perform(get("/api/paper/account")).andExpect(status().isOk).andExpect(jsonPath("$.initialCapital").value(100000000))
    }
}
