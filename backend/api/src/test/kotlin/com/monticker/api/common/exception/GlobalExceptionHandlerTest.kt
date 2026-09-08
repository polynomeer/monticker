package com.monticker.api.common.exception

import com.monticker.api.common.aop.RiskLimitException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
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
    fun `증권사 계좌 미연동 IllegalStateException은 409를 반환한다`() {
        // BrokerageService#getAccount / ConditionalOrderService — 계좌 미연동은
        // 프론트가 정상 상태로 취급하는 케이스라 500이 아닌 409여야 한다.
        val resp = handler.handleIllegalState(IllegalStateException("연동된 증권사 계좌가 없습니다."))
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
    fun `MissingServletRequestParameterException은 400을 반환한다 (누락된 파라미터명 포함)`() {
        // @RequestParam token: String 처럼 필수 쿼리 파라미터가 아예 없을 때 —
        // 예: POST /api/auth/verify-email 을 token 없이 호출하는 경우.
        val resp = handler.handleMissingParam(
            MissingServletRequestParameterException("token", "String")
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(resp.body?.message).contains("token")
    }

    @Test
    fun `MethodArgumentTypeMismatchException은 400을 반환한다 (형식이 안 맞는 파라미터명 포함)`() {
        // MethodParameter 생성자는 실제 메서드 리플렉션이 필요 — 문자열 파라미터가 있는
        // 아무 메서드나 빌려 쓴다(값 자체는 검사하지 않음).
        val method = this::class.java.getDeclaredMethod("dummyMethodForParam", String::class.java)
        val param = MethodParameter(method, 0)
        val resp = handler.handleTypeMismatch(
            MethodArgumentTypeMismatchException("abc", Long::class.java, "stockId", param, IllegalArgumentException())
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(resp.body?.message).contains("stockId")
    }

    @Suppress("unused")
    private fun dummyMethodForParam(value: String) {}

    @Test
    fun `ErrorResponse에 timestamp가 포함된다`() {
        val resp = handler.handleIllegalArgument(IllegalArgumentException("test"))
        assertThat(resp.body?.timestamp).isNotNull()
        assertThat(resp.body?.status).isEqualTo(400)
    }
}
