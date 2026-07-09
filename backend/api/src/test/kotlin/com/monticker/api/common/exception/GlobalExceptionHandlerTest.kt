package com.monticker.api.common.exception

import com.monticker.api.common.aop.RiskLimitException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.server.ResponseStatusException

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `IllegalArgumentException은 400을 반환한다`() {
        val resp = handler.handleIllegalArgument(IllegalArgumentException("잘못된 입력"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(resp.body?.message).isEqualTo("잘못된 입력")
    }

    @Test
    fun `NoSuchElementException은 404를 반환한다`() {
        val resp = handler.handleNotFound(NoSuchElementException("리소스 없음"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `BadCredentialsException은 401을 반환한다`() {
        val resp = handler.handleBadCredentials(BadCredentialsException("틀린 비밀번호"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `AccessDeniedException은 403을 반환한다`() {
        val resp = handler.handleAccessDenied(AccessDeniedException("권한 없음"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `RiskLimitException은 422를 반환한다`() {
        val resp = handler.handleRiskLimit(RiskLimitException("포지션 한도 초과"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(resp.body?.message).contains("포지션 한도 초과")
    }

    @Test
    fun `비즈니스 규칙 IllegalStateException은 409를 반환한다`() {
        val resp = handler.handleIllegalState(IllegalStateException("현재가 조회 불가: stockId=1"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `서버 내부 IllegalStateException은 500을 반환한다`() {
        val resp = handler.handleIllegalState(IllegalStateException("내부 컴포넌트 오류"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @Test
    fun `처리되지 않은 예외는 500을 반환한다`() {
        val resp = handler.handleGeneral(RuntimeException("예상치 못한 오류"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(resp.body?.message).isEqualTo("서버 내부 오류가 발생했습니다")
    }

    @Test
    fun `ResponseStatusException은 해당 HTTP 상태를 그대로 반환한다`() {
        val resp = handler.handleResponseStatus(
            ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded")
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `ErrorResponse에 timestamp가 포함된다`() {
        val resp = handler.handleIllegalArgument(IllegalArgumentException("test"))
        assertThat(resp.body?.timestamp).isNotNull()
        assertThat(resp.body?.status).isEqualTo(400)
    }
}
