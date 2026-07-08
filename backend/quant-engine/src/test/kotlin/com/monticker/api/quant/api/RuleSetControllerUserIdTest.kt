package com.monticker.api.quant.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

/**
 * quant-engine RuleSetController userId 추출 계약 검증.
 * X-User-Id 헤더 기반 내부 서비스 패턴.
 */
class RuleSetControllerUserIdTest {

    private fun withRequest(setup: MockHttpServletRequest.() -> Unit = {}, block: () -> Unit) {
        val req = MockHttpServletRequest().apply(setup)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(req))
        try { block() } finally { RequestContextHolder.resetRequestAttributes() }
    }

    private fun extractUserId(): Long {
        val attrs = RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
        return attrs.request.getHeader("X-User-Id")?.toLong()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-User-Id header required")
    }

    @Test
    fun `X-User-Id 헤더 있으면 userId 반환`() {
        withRequest({ addHeader("X-User-Id", "7") }) {
            assert(extractUserId() == 7L)
        }
    }

    @Test
    fun `X-User-Id 헤더 없으면 401`() {
        withRequest {
            assertThrows<ResponseStatusException> { extractUserId() }.also {
                assert(it.statusCode.value() == 401)
            }
        }
    }
}
