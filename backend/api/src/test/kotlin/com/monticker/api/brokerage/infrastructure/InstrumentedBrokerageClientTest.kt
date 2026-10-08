package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.common.exception.ExternalServiceUnavailableException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDate

/** 계좌 상태의 API 지연·마지막 오류 — 데코레이터가 호출 결과를 바꾸지 않는다는 것이 먼저다. */
class InstrumentedBrokerageClientTest {
    private val delegate = mockk<BrokerageClient>()
    private val tracker = BrokerCallHealthTracker()
    private val client = InstrumentedBrokerageClient(delegate, BrokerageProvider.KIS, tracker)
    private val creds = BrokerageCredentials(BrokerageToken("tok", 0), "app-key", "secret", "12345678-01")
    private val req = BrokerageOrderRequest("005930", "BUY", "MARKET", 1)

    private fun health() = tracker.get(BrokerageProvider.KIS, "app-key", "12345678-01")

    @Test
    fun `delegates movesRealMoney instead of falling back to the interface default`() {
        every { delegate.movesRealMoney } returns false
        assertThat(client.movesRealMoney).isFalse()
        every { delegate.movesRealMoney } returns true
        assertThat(client.movesRealMoney).isTrue()
    }

    @Test
    fun `returns the delegate result unchanged and records latency without an error`() {
        val result = BrokerageOrderResult.accepted("PG-1", "ref")
        every { delegate.submitOrder(creds, req, "mt-1") } returns result

        assertThat(client.submitOrder(creds, req, "mt-1")).isSameAs(result)
        verify(exactly = 1) { delegate.submitOrder(creds, req, "mt-1") }
        val h = health()!!
        assertThat(h.lastOperation).isEqualTo(BrokerCallOperation.SUBMIT_ORDER)
        assertThat(h.lastErrorCode).isNull()
        assertThat(h.lastSuccessAt).isNotNull()
        assertThat(h.lastLatencyMs).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `indeterminate and rejected submits are recorded as fixed codes but returned as is`() {
        val unknown = BrokerageOrderResult.indeterminate("read timeout account 12345678-01")
        every { delegate.submitOrder(any(), any(), any()) } returns unknown
        assertThat(client.submitOrder(creds, req, "mt-2")).isSameAs(unknown)
        assertThat(health()!!.lastErrorCode).isEqualTo(BrokerErrorCode.SUBMIT_INDETERMINATE)

        every { delegate.submitOrder(any(), any(), any()) } returns BrokerageOrderResult.rejected("잔고 부족")
        client.submitOrder(creds, req, "mt-3")
        assertThat(health()!!.lastErrorCode).isEqualTo(BrokerErrorCode.ORDER_REJECTED)
    }

    @Test
    fun `rethrows the very same exception and keeps only a fixed code`() {
        val boom = ExternalServiceUnavailableException("kis", "KIS 잔고 조회 실패 계좌 12345678-01", ResourceAccessException("x", SocketTimeoutException("Read timed out")))
        every { delegate.getBalance(creds) } throws boom

        assertThatThrownBy { client.getBalance(creds) }.isSameAs(boom)
        val h = health()!!
        assertThat(h.lastErrorCode).isEqualTo(BrokerErrorCode.TIMEOUT)
        assertThat(h.lastErrorOperation).isEqualTo(BrokerCallOperation.GET_BALANCE)
        assertThat(h.toString()).doesNotContain("12345678").doesNotContain("app-key")
    }

    @Test
    fun `a later success keeps the last error so the screen can say when it happened`() {
        every { delegate.getOrderStatus(creds, "PG") } throws HttpServerErrorException(HttpStatus.BAD_GATEWAY) andThen
            BrokerageOrderStatus("PG", "FILLED", 1, BigDecimal.ONE)
        runCatching { client.getOrderStatus(creds, "PG") }
        client.getOrderStatus(creds, "PG")
        val h = health()!!
        assertThat(h.lastErrorCode).isEqualTo(BrokerErrorCode.BROKER_5XX)
        assertThat(h.lastSuccessAt).isAfterOrEqualTo(h.lastErrorAt)
    }

    @Test
    fun `findOrders null is a lookup failure, an empty list is not`() {
        every { delegate.findOrders(creds, any(), any(), any()) } returns null
        assertThat(client.findOrders(creds, LocalDate.now(), "005930", "BUY")).isNull()
        assertThat(health()!!.lastErrorCode).isEqualTo(BrokerErrorCode.LOOKUP_FAILED)
    }

    @Test
    fun `token issuance is passed through and not keyed by account`() {
        every { delegate.issueToken("k", "s") } returns BrokerageToken("t", 60)
        assertThat(client.issueToken("k", "s").accessToken).isEqualTo("t")
        assertThat(tracker.size()).isZero()
    }

    @Test
    fun `accounts are kept apart by app key even with the same account number`() {
        every { delegate.cancelOrder(any(), any(), any()) } returns BrokerageCancelResult(true)
        client.cancelOrder(creds, "PG", null)
        assertThat(tracker.get(BrokerageProvider.KIS, "other-key", "12345678-01")).isNull()
        assertThat(tracker.get(BrokerageProvider.TOSS, "app-key", "12345678-01")).isNull()
        assertThat(tracker.get(BrokerageProvider.KIS, null, "12345678-01")).isNull()
    }

    @Test
    fun `classifies by type and status only`() {
        assertThat(BrokerErrorCode.classify(HttpClientErrorException(HttpStatus.UNAUTHORIZED))).isEqualTo(BrokerErrorCode.AUTH_FAILED)
        assertThat(BrokerErrorCode.classify(HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))).isEqualTo(BrokerErrorCode.RATE_LIMITED)
        assertThat(BrokerErrorCode.classify(HttpClientErrorException(HttpStatus.BAD_REQUEST))).isEqualTo(BrokerErrorCode.BROKER_4XX)
        assertThat(BrokerErrorCode.classify(ResourceAccessException("refused"))).isEqualTo(BrokerErrorCode.NETWORK)
        val open = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("kis"))
        assertThat(BrokerErrorCode.classify(ExternalServiceUnavailableException("kis", "open", open))).isEqualTo(BrokerErrorCode.CIRCUIT_OPEN)
        assertThat(BrokerErrorCode.classify(ExternalServiceUnavailableException("kis", "down"))).isEqualTo(BrokerErrorCode.BROKER_UNAVAILABLE)
        assertThat(BrokerErrorCode.classify(IllegalStateException("anything"))).isEqualTo(BrokerErrorCode.UNKNOWN)
    }

    @Test
    fun `the tracker evicts stale entries beyond its size cap`() {
        val t = BrokerCallHealthTracker()
        val old = java.time.Instant.parse("2026-10-01T00:00:00Z")
        t.clock = java.time.Clock.fixed(old, java.time.ZoneOffset.UTC)
        repeat(BrokerCallHealthTracker.MAX_ENTRIES) { t.record("k$it", BrokerCallOperation.GET_BALANCE, java.time.Duration.ofMillis(5), null) }
        t.clock = java.time.Clock.fixed(old.plus(java.time.Duration.ofDays(2)), java.time.ZoneOffset.UTC)
        t.record("fresh", BrokerCallOperation.GET_BALANCE, java.time.Duration.ofMillis(5), null)
        assertThat(t.size()).isEqualTo(1)
    }
}
