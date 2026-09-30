package com.monticker.api.subscription.infrastructure.pg

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-053 — PG 클라이언트의 실패 분류와 서킷브레이커를 **실제 HTTP 서버를 세워** 확인한다.
 *
 * mockk로 RestClient를 흉내내면 정작 알고 싶은 것(타임아웃이 어떤 예외로 오는가,
 * 그 예외가 브레이커에 집계되는가)을 전부 비켜간다. HttpTimeoutsTest와 같은 방식이다.
 *
 * 이 테스트가 없던 동안 TossPgClient의 테스트 파일은 0개였다 — 실제 돈이 오가는 유일한
 * 외부 연동인데도.
 */
class TossPgClientTest {

    private lateinit var server: HttpServer
    private val delayMs = AtomicInteger(0)
    private val statusCode = AtomicInteger(200)
    private val hits = AtomicInteger(0)

    /** 브레이커 설정은 운영과 같은 모양이되, 테스트에서 기다릴 수 있게 임계만 줄인다. */
    private fun registry(slowCallMs: Long = 300, window: Int = 4) = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50f)
            .slowCallRateThreshold(50f)
            .slowCallDurationThreshold(Duration.ofMillis(slowCallMs))
            .slidingWindowSize(window)
            .minimumNumberOfCalls(window)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .recordExceptions(Exception::class.java)
            .build()
    )

    private fun client(reg: CircuitBreakerRegistry) =
        TossPgClient("test_sk_stub", "http://127.0.0.1:${server.address.port}", reg)

    @BeforeEach
    fun startStub() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            hits.incrementAndGet()
            ex.requestBody.readBytes()
            Thread.sleep(delayMs.get().toLong())
            val code = statusCode.get()
            val body = if (code == 200)
                """{"paymentKey":"pay_stub","orderId":"renewal_1_100","status":"DONE","totalAmount":9900,"method":"카드"}"""
            else """{"code":"STUB_ERROR","message":"stub"}"""
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }
        server.start()
        delayMs.set(0); statusCode.set(200); hits.set(0)
    }

    @AfterEach
    fun stop() = server.stop(0)

    // ── 정상 경로 ────────────────────────────────────────────────────────────

    @Test
    fun `정기결제가 DONE이면 성공과 paymentKey를 돌려준다`() {
        val result = client(registry()).chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_100", "PRO")

        assertThat(result.success).isTrue()
        assertThat(result.pgTransactionId).isEqualTo("pay_stub")
        assertThat(result.failureKind).isNull()
    }

    // ── 실패 분류 ────────────────────────────────────────────────────────────

    @Test
    fun `4xx는 DECLINED다 — PG가 요청을 이해하고 거절했다`() {
        statusCode.set(400)

        val result = client(registry()).chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_100", "PRO")

        assertThat(result.success).isFalse()
        assertThat(result.failureKind).isEqualTo(PaymentFailureKind.DECLINED)
    }

    @Test
    fun `5xx는 INDETERMINATE다 — 승인 직후 장애일 수 있어 청구 여부를 단정할 수 없다`() {
        statusCode.set(500)

        val result = client(registry()).chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_100", "PRO")

        assertThat(result.failureKind).isEqualTo(PaymentFailureKind.INDETERMINATE)
    }

    @Test
    fun `연결 자체가 안 되면 UNAVAILABLE이다 — 청구되지 않았음이 확실하다`() {
        val port = server.address.port
        server.stop(0)                      // 포트를 닫아 connection refused를 만든다

        val result = TossPgClient("sk", "http://127.0.0.1:$port", registry())
            .chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_100", "PRO")

        assertThat(result.failureKind).isEqualTo(PaymentFailureKind.UNAVAILABLE)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }  // AfterEach용
    }

    // ── 서킷브레이커 ─────────────────────────────────────────────────────────

    @Test
    fun `PG가 죽지 않고 느려지기만 해도 브레이커가 열린다`() {
        // 이것이 P0-2의 핵심이다. 호출은 전부 200으로 성공하므로 failureRate는 0이고,
        // slow-call 집계만이 브레이커를 열 수 있다. 결제 경로에는 이게 아예 없었다.
        val reg = registry(slowCallMs = 200, window = 4)
        val pg = client(reg)
        delayMs.set(400)

        repeat(4) { pg.chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_$it", "PRO") }

        assertThat(reg.circuitBreaker("tossPg").state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThat(reg.circuitBreaker("tossPg").metrics.numberOfFailedCalls).isZero()  // 실패는 0건인데도
    }

    @Test
    fun `브레이커가 열리면 PG를 찌르지 않고 즉시 UNAVAILABLE을 돌려준다`() {
        val reg = registry(slowCallMs = 200, window = 4)
        val pg = client(reg)
        delayMs.set(400)
        repeat(4) { pg.chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_$it", "PRO") }
        val hitsBeforeOpen = hits.get()

        val started = System.nanoTime()
        val blocked = pg.chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_open", "PRO")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(blocked.failureKind).isEqualTo(PaymentFailureKind.UNAVAILABLE)
        assertThat(hits.get()).isEqualTo(hitsBeforeOpen)   // 서버에 닿지 않았다 = 청구될 수 없다
        assertThat(elapsedMs).isLessThan(100)              // 스레드가 PG를 기다리며 쌓이지 않는다
    }

    @Test
    fun `느린 PG가 다른 호출의 스레드를 잡아먹지 않는다 — 열린 뒤 10건이 즉시 반환된다`() {
        val reg = registry(slowCallMs = 200, window = 4)
        val pg = client(reg)
        delayMs.set(400)
        repeat(4) { pg.chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_1_$it", "PRO") }

        val started = System.nanoTime()
        repeat(10) { pg.chargeBilling("bk", "ck", BigDecimal("9900"), "renewal_2_$it", "PRO") }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // 브레이커가 없었다면 10 × 400ms = 4초. 열려 있으면 거의 0이다.
        assertThat(elapsedMs).isLessThan(500)
    }

    // ── orderId 재조회 ───────────────────────────────────────────────────────

    @Test
    fun `orderId로 결제를 되짚어 DONE을 확인한다`() {
        val status = client(registry()).findPaymentByOrderId("renewal_1_100")

        assertThat(status.found).isTrue()
        assertThat(status.status).isEqualTo("DONE")
        assertThat(status.paymentKey).isEqualTo("pay_stub")
        assertThat(status.lookupFailed).isFalse()
    }

    @Test
    fun `404는 권위 있는 결제 없음이다 — 재청구해도 안전하다`() {
        statusCode.set(404)

        val status = client(registry()).findPaymentByOrderId("renewal_1_100")

        assertThat(status.found).isFalse()
        assertThat(status.lookupFailed).isFalse()
    }

    @Test
    fun `조회 실패와 결제 없음을 섞지 않는다 — 500은 lookupFailed다`() {
        // 이 둘을 같게 취급하면 "조회가 실패했을 뿐인데 결제 안 된 걸로 읽고 재청구"가 된다.
        statusCode.set(500)

        val status = client(registry()).findPaymentByOrderId("renewal_1_100")

        assertThat(status.found).isFalse()
        assertThat(status.lookupFailed).isTrue()
    }
}
