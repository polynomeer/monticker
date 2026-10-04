package com.monticker.api.brokerage.infrastructure

import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate

/**
 * ADR-056 — 대조가 기대는 증권사 주문 목록 파싱을 실제 HTTP 응답(JSON)으로 확인한다.
 *
 * 핵심 불변식: **한 건이라도 해석하지 못하면 "조회 실패"(null)** 다. 빠뜨리고 넘어가면 실제로 체결된 주문이 목록에서
 * 사라져 2분 뒤 "미접수"로 확정되고, 사용자는 재주문한다.
 */
class BrokerFindOrdersHttpTest {

    private var server: HttpServer? = null
    private val creds = BrokerageCredentials(BrokerageToken("t", 3600), "k", "s", "12345678-01", providerAccountRef = "7")
    private val day = LocalDate.of(2026, 10, 5)

    private fun serve(body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterEach fun stop() { server?.stop(0) }

    private fun kis(body: String) = KisBrokerageClient(serve(body), CircuitBreakerRegistry.ofDefaults())
    private fun toss(body: String) = TossBrokerageClient(serve(body), CircuitBreakerRegistry.ofDefaults())

    @Test
    fun `KIS — ord_dt·ord_tmd를 KST 주문시각으로, ord_gno_brno를 취소용 참조로 읽는다`() {
        val c = kis("""{"rt_cd":"0","msg1":"ok","output1":[
            {"odno":"0000123","pdno":"005930","sll_buy_dvsn_cd":"01","ord_qty":"10","ord_unpr":"0","tot_ccld_qty":"10",
             "rmn_qty":"0","rjct_qty":"0","avg_prvs":"70100","ord_dt":"20261005","ord_tmd":"093015","ord_gno_brno":"06010"}]}""")

        val s = c.findOrders(creds, day, "005930", "SELL")!!.single()

        assertThat(s.brokerOrderId).isEqualTo("0000123")
        assertThat(s.brokerOrderRef).isEqualTo("06010")
        assertThat(s.side).isEqualTo("SELL")
        assertThat(s.orderedAt).isEqualTo(Instant.parse("2026-10-05T00:30:15Z"))   // 09:30:15 KST
        assertThat(s.status).isEqualTo("FILLED")
        assertThat(s.price).isNull()                                               // 시장가 단가 0
        assertThat(s.avgFillPrice).isEqualByComparingTo(BigDecimal("70100"))
    }

    @Test
    fun `KIS — 주문시각이 비어 있는 항목이 하나라도 있으면 조회 실패(null)다`() {
        val c = kis("""{"rt_cd":"0","output1":[
            {"odno":"1","pdno":"005930","sll_buy_dvsn_cd":"01","ord_qty":"10","ord_dt":"20261005","ord_tmd":"093015"},
            {"odno":"2","pdno":"005930","sll_buy_dvsn_cd":"01","ord_qty":"10","ord_dt":"20261005","ord_tmd":""}]}""")

        assertThat(c.findOrders(creds, day, "005930", "SELL")).isNull()
    }

    @Test
    fun `KIS — rt_cd 실패 응답은 빈 목록이 아니라 조회 실패다`() {
        assertThat(kis("""{"rt_cd":"1","msg1":"조회 오류","output1":[]}""").findOrders(creds, day, "005930", "SELL")).isNull()
    }

    @Test
    fun `KIS — 주문이 정말 없으면 빈 목록(주문 없음)이다`() {
        assertThat(kis("""{"rt_cd":"0","output1":[]}""").findOrders(creds, day, "005930", "SELL")).isEmpty()
    }

    @Test
    fun `Toss — 스펙 예시 형식(오프셋 포함 KST) orderedAt을 읽고, 해석 못 하는 항목이 있으면 조회 실패다`() {
        val good = """{"result":{"orders":[{"orderId":"o-1","symbol":"005930","side":"SELL","status":"FILLED","quantity":"10",
            "price":null,"orderedAt":"2026-10-05T09:30:00.000+09:00","execution":{"filledQuantity":"10","averageFilledPrice":"70000"}}],
            "nextCursor":null,"hasNext":false}}"""
        val s = toss(good).findOrders(creds, day, "005930", "SELL")!!
        // OPEN·CLOSED 두 그룹을 같은 응답으로 받으므로 2건 — 해석만 확인한다
        assertThat(s.first().orderedAt).isEqualTo(Instant.parse("2026-10-05T00:30:00Z"))
        assertThat(s.first().status).isEqualTo("FILLED")

        val bad = good.replace("2026-10-05T09:30:00.000+09:00", "2026-10-05 09:30:00")
        assertThat(toss(bad).findOrders(creds, day, "005930", "SELL")).isNull()
    }
}
