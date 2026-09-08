package com.monticker.api.common.exception

import com.monticker.api.common.aop.RiskLimitException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.concurrent.RejectedExecutionException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    // ── 4xx — 클라이언트 오류 ────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fields = e.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        return error(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다", fields.joinToString("; "))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException) =
        error(HttpStatus.BAD_REQUEST, "요청 본문을 파싱할 수 없습니다")

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException) =
        error(HttpStatus.BAD_REQUEST, "필수 헤더가 없습니다: ${e.headerName}")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException) =
        error(HttpStatus.BAD_REQUEST, "필수 파라미터가 없습니다: ${e.parameterName}")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException) =
        error(HttpStatus.BAD_REQUEST, "파라미터 형식이 올바르지 않습니다: ${e.name}")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException) =
        error(HttpStatus.BAD_REQUEST, e.message ?: "잘못된 요청입니다")

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException) =
        error(HttpStatus.NOT_FOUND, e.message ?: "리소스를 찾을 수 없습니다")

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(e: BadCredentialsException) =
        error(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다")

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException) =
        error(HttpStatus.FORBIDDEN, "접근 권한이 없습니다")

    @ExceptionHandler(RiskLimitException::class)
    fun handleRiskLimit(e: RiskLimitException) =
        error(HttpStatus.UNPROCESSABLE_ENTITY, e.message ?: "리스크 한도 초과")

    // 백테스트 실행기(backtestExecutor) 큐가 가득 찼을 때 — 스레드 풀 고갈 대신 클라이언트에게
    // 429로 알려 재시도를 유도한다(BacktestController).
    @ExceptionHandler(RejectedExecutionException::class)
    fun handleRejectedExecution(e: RejectedExecutionException) =
        error(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.")

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException) =
        error(HttpStatus.valueOf(e.statusCode.value()), e.reason ?: e.message)

    // ── 409 ─────────────────────────────────────────────────────────────

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<ErrorResponse> {
        // 비즈니스 규칙 위반(주문 불가 등)은 409, 서버 내부 상태 오류는 500
        val message = e.message ?: "처리할 수 없는 상태입니다"
        val isBusinessRule = message.contains("현재가") || message.contains("보유") ||
                message.contains("잔고") || message.contains("불가") ||
                message.contains("없음")
        return if (isBusinessRule) {
            error(HttpStatus.CONFLICT, message)
        } else {
            log.error("[서버 오류] IllegalStateException", e)
            error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다")
        }
    }

    // ── 5xx — 서버 오류 ──────────────────────────────────────────────────

    @ExceptionHandler(Exception::class)
    fun handleGeneral(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("[서버 오류] 처리되지 않은 예외: {}", e.message, e)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다")
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private fun error(
        status: HttpStatus,
        message: String,
        detail: String? = null,
    ) = ResponseEntity
        .status(status)
        .body(ErrorResponse(status.value(), message, detail))
}

data class ErrorResponse(
    val status: Int,
    val message: String,
    val detail: String? = null,
    val timestamp: Instant = Instant.now(),
)
