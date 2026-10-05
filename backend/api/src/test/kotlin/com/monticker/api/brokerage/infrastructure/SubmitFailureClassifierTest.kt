package com.monticker.api.brokerage.infrastructure

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

/**
 * ADR-056 — "증권사에 접수되지 않았음이 확실한가". 확실할 때만 REJECTED, 그 밖은 전부 INDETERMINATE.
 * 예전엔 이 모든 경우가 REJECTED였다.
 */
class SubmitFailureClassifierTest {

    private fun outcome(e: Throwable) = SubmitFailureClassifier.classify(e).outcome

    @Test
    fun `서킷 OPEN은 호출하지 않았으니 거부`() {
        val open = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("t").also { it.transitionToOpenState() })
        assertThat(outcome(open)).isEqualTo(SubmitOutcome.REJECTED)
    }

    @Test
    fun `연결 거부·연결 타임아웃은 요청이 나가지 않았으니 거부`() {
        assertThat(outcome(ResourceAccessException("x", ConnectException("refused")))).isEqualTo(SubmitOutcome.REJECTED)
        assertThat(outcome(ResourceAccessException("x", HttpConnectTimeoutException("connect timed out")))).isEqualTo(SubmitOutcome.REJECTED)
    }

    @Test
    fun `읽기 타임아웃은 요청이 나갔을 수 있으니 불명 — 이게 예전의 이중 주문 경로다`() {
        assertThat(outcome(ResourceAccessException("x", HttpTimeoutException("request timed out")))).isEqualTo(SubmitOutcome.INDETERMINATE)
        assertThat(outcome(ResourceAccessException("x", IOException("connection reset")))).isEqualTo(SubmitOutcome.INDETERMINATE)
    }

    @Test
    fun `4xx는 증권사가 읽고 거절했으니 거부, 단 408은 불명`() {
        assertThat(outcome(HttpClientErrorException(HttpStatus.BAD_REQUEST))).isEqualTo(SubmitOutcome.REJECTED)
        assertThat(outcome(HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))).isEqualTo(SubmitOutcome.REJECTED)
        assertThat(outcome(HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT))).isEqualTo(SubmitOutcome.INDETERMINATE)
    }

    @Test
    fun `5xx는 접수 여부를 모르니 불명`() {
        assertThat(outcome(HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))).isEqualTo(SubmitOutcome.INDETERMINATE)
        assertThat(outcome(HttpServerErrorException(HttpStatus.BAD_GATEWAY))).isEqualTo(SubmitOutcome.INDETERMINATE)
    }

    @Test
    fun `모르는 예외는 불명 쪽으로`() {
        assertThat(outcome(IllegalStateException("parse error after 200"))).isEqualTo(SubmitOutcome.INDETERMINATE)
    }
}
