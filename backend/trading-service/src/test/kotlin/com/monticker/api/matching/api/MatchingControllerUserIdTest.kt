package com.monticker.api.matching.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

/**
 * trading-service 컨트롤러의 userId() 헬퍼 — X-User-Id 헤더 추출 계약 검증.
 *
 * JWT 없이 X-User-Id 헤더로 userId를 전달하는 내부 서비스 패턴.
 * api 게이트웨이가 JWT 검증 후 헤더를 세팅해서 전달한다.
 */
class MatchingControllerUserIdTest {

    /** 테스트용 RequestContextHolder 셋업 후 블록 실행, 항상 정리 */
    private fun withRequest(setup: MockHttpServletRequest.() -> Unit = {}, block: () -> Unit) {
        val req = MockHttpServletRequest().apply(setup)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(req))
        try { block() } finally { RequestContextHolder.resetRequestAttributes() }
    }

    private fun extractUserId(): Long {
        val attrs = RequestContextHolder.currentRequestAttributes()
            as ServletRequestAttributes
        return attrs.request.getHeader("X-User-Id")?.toLong()
            ?: throw ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "X-User-Id header required"
            )
    }

    @Test
    fun `X-User-Id 헤더가 있으면 userId를 반환한다`() {
        withRequest({ addHeader("X-User-Id", "42") }) {
            assert(extractUserId() == 42L) { "42가 반환돼야 한다" }
        }
    }

    @Test
    fun `X-User-Id 헤더가 없으면 UNAUTHORIZED ResponseStatusException을 던진다`() {
        withRequest {
            assertThrows<ResponseStatusException> { extractUserId() }.also { ex ->
                assert(ex.statusCode.value() == 401) { "401이어야 한다" }
            }
        }
    }

    @Test
    fun `X-User-Id 헤더가 숫자가 아니면 NumberFormatException을 던진다`() {
        withRequest({ addHeader("X-User-Id", "not-a-number") }) {
            assertThrows<NumberFormatException> { extractUserId() }
        }
    }

    @Test
    fun `다양한 userId 값을 올바르게 파싱한다`() {
        listOf(1L, 99999L, Long.MAX_VALUE / 2).forEach { expected ->
            withRequest({ addHeader("X-User-Id", expected.toString()) }) {
                assert(extractUserId() == expected)
            }
        }
    }
}
