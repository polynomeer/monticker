package com.monticker.api.common.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 모든 요청에 고유한 Request ID를 부여한다.
 *
 * - 클라이언트가 X-Request-Id 헤더를 보내면 그 값을 사용한다.
 * - 없으면 UUID를 생성한다.
 * - MDC에 "requestId"로 등록해 로그에 자동 포함된다.
 * - 응답 헤더 X-Request-Id로 클라이언트에 반환해 로그 추적을 돕는다.
 */
@Component
@Order(1)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val requestId = request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        MDC.put("requestId", requestId)
        response.setHeader("X-Request-Id", requestId)

        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("requestId")
        }
    }
}
