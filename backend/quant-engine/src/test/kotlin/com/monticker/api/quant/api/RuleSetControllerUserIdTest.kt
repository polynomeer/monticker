package com.monticker.api.quant.api

import com.monticker.api.quant.application.RuleSetResponse
import com.monticker.api.quant.application.RuleSetService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * RuleSetController.userId() 추출 계약 검증.
 *
 * 실제 컨트롤러는 X-User-Id 헤더가 아니라 SecurityContextHolder의
 * Authentication.principal(Long)에서 userId를 얻는다 (JWT 기반 인증 전제,
 * 게이트웨이 뒤 내부 서비스인 trading-service의 X-User-Id 패턴과는 다르다).
 * MockMvc standalone 설정으로 실제 컨트롤러를 호출해 이 계약을 검증한다.
 */
class RuleSetControllerUserIdTest {

    private val service: RuleSetService = mockk()
    private val mockMvc: MockMvc =
        MockMvcBuilders.standaloneSetup(RuleSetController(service)).build()

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(userId: Long) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
    }

    @Test
    fun `인증된 요청은 SecurityContext의 principal을 userId로 사용해 서비스에 위임한다`() {
        authenticateAs(7L)
        every { service.findByUser(7L) } returns emptyList()

        mockMvc.perform(get("/api/quant/rulesets"))
            .andExpect(status().isOk)

        verify { service.findByUser(7L) }
    }

    @Test
    fun `다른 userId로 인증하면 다른 userId로 조회한다`() {
        authenticateAs(99L)
        every { service.findByUser(99L) } returns listOf(
            RuleSetResponse(
                id = "rs-1", userId = 99L, name = "테스트 룰셋", description = null,
                version = 1, status = "ACTIVE", ruleDefinition = "{}", universeJson = "{}",
                fingerprint = "abc", versionCount = 1,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            )
        )

        mockMvc.perform(get("/api/quant/rulesets"))
            .andExpect(status().isOk)

        verify { service.findByUser(99L) }
        verify(exactly = 0) { service.findByUser(neq(99L)) }
    }

    @Test
    fun `존재하지 않는 룰셋을 조회하면 404를 반환한다`() {
        authenticateAs(7L)
        every { service.findById("missing", 7L) } throws NoSuchElementException("RuleSet missing not found")

        mockMvc.perform(get("/api/quant/rulesets/missing"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `인증 정보 없이 요청하면 컨트롤러가 처리하지 못하고 실패한다`() {
        // 알려진 갭: RuleSetController.userId()는 MatchingController의 X-User-Id
        // 분기와 달리 인증 부재를 방어하지 않는다 (principal 추출 시 NPE).
        // 게이트웨이가 인증되지 않은 요청을 걸러낸다는 전제에 의존하는 것으로 보이나,
        // 이 컨트롤러 단독으로는 401이 아니라 예외로 죽는다 — 확인이 필요한 지점.
        assertThrows(jakarta.servlet.ServletException::class.java) {
            mockMvc.perform(get("/api/quant/rulesets"))
        }
    }
}
