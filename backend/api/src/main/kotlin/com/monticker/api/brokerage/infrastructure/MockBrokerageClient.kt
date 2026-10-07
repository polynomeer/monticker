package com.monticker.api.brokerage.infrastructure

import com.monticker.api.common.calendar.KrxCalendar
import com.monticker.api.common.calendar.TradingCalendar
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * KIS Open API Mock 구현체.
 * - 시장가: 현재가 ±0.05% 슬리피지 적용 후 즉시 체결
 * - 지정가: 인메모리 주문 상태 관리 (폴링으로 확인 가능)
 * - T+2 정산: 체결 시 현재일+2 영업일로 settlement 아이템 생성
 */
@Primary
@Component
@ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "true", matchIfMissing = true)
class MockBrokerageClient(
    private val jdbc: JdbcTemplate,
    // ADR-056 로컬 검증용 — 이 종목들의 주문은 증권사(인메모리)에는 접수되지만 응답이 유실된 것처럼
    // INDETERMINATE를 돌려준다. 결과 불명 → 대조 잡 매칭 → 해소 흐름을 실제 앱에서 재현하기 위함이다.
    @Value("\${app.brokerage.mock.indeterminate-symbols:}") indeterminateSymbolsRaw: String = "",
    // ADR-086 — 가짜 증권사도 결제일은 KRX 캘린더로. 단위 테스트에서 생략하면 주말만 건너뛴다.
    private val calendar: TradingCalendar = KrxCalendar.empty(),
) : BrokerageClient {

    // ADR-060 — 개발용 가짜 증권사. 합성 시세로도 조건부 주문을 만들고 발동시킬 수 있다.
    override val movesRealMoney: Boolean = false

    private val indeterminateSymbols = indeterminateSymbolsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private val log = LoggerFactory.getLogger(javaClass)

    // 인메모리 주문장: pgOrderId → status
    private val orderStore = ConcurrentHashMap<String, MockOrder>()
    // 체결 내역: pgOrderId → settlement
    private val settlementStore = ConcurrentHashMap<String, BrokerageSettlementItem>()

    data class MockOrder(
        val pgOrderId: String,
        var status: String,
        val request: BrokerageOrderRequest,
        var filledQty: Int = 0,
        var avgFillPrice: BigDecimal? = null,
        val settleDate: LocalDate,
        val orderedAt: Instant = Instant.now(),
    )

    override fun issueToken(appKey: String, appSecret: String): BrokerageToken {
        log.info("[MockKIS] 토큰 발급")
        return BrokerageToken(
            accessToken = "mock_token_${UUID.randomUUID()}",
            expiresIn   = 86400L,
        )
    }

    override fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest, clientOrderId: String): BrokerageOrderResult {
        val pgOrderId = "KIS${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val settleDate = calendar.settlementDate(Instant.now())

        val order = MockOrder(pgOrderId = pgOrderId, status = "SUBMITTED", request = request, settleDate = settleDate)

        if (request.orderType == "MARKET") {
            // 시장가: 현재가로 즉시 체결
            val currentPrice = getCurrentPrice(request.symbol)
                ?: return BrokerageOrderResult.rejected("현재가 조회 실패")

            val slippage    = if (request.side == "BUY") BigDecimal("1.0005") else BigDecimal("0.9995")
            val fillPrice   = currentPrice.multiply(slippage).setScale(0, RoundingMode.HALF_UP)
            val fee         = fillPrice.multiply(BigDecimal(request.quantity)).multiply(FEE_RATE).setScale(0, RoundingMode.UP)
            val tax         = if (request.side == "SELL") fillPrice.multiply(BigDecimal(request.quantity)).multiply(SELL_TAX_RATE).setScale(0, RoundingMode.UP) else BigDecimal.ZERO

            order.status       = "FILLED"
            order.filledQty    = request.quantity
            order.avgFillPrice = fillPrice

            settlementStore[pgOrderId] = BrokerageSettlementItem(
                pgOrderId  = pgOrderId,
                symbol     = request.symbol,
                side       = request.side,
                quantity   = request.quantity,
                fillPrice  = fillPrice,
                fee        = fee,
                tax        = tax,
                settleDate = settleDate,
            )
            log.info("[MockKIS] 시장가 체결: {} {} {}주 @ {} (fee={} tax={})", pgOrderId, request.side, request.quantity, fillPrice, fee, tax)
        } else {
            // 지정가: SUBMITTED 상태로 보관 (getOrderStatus 호출 시 조건부 체결)
            log.info("[MockKIS] 지정가 접수: {} {} {}주 @ {}", pgOrderId, request.side, request.quantity, request.limitPrice)
        }

        orderStore[pgOrderId] = order
        if (request.symbol in indeterminateSymbols) {
            log.warn("[MockKIS] 응답 유실 시뮬레이션: {} 접수됐지만 INDETERMINATE 반환 (clientOrderId={})", pgOrderId, clientOrderId)
            return BrokerageOrderResult.indeterminate("모의 응답 유실")
        }
        return BrokerageOrderResult.accepted(pgOrderId)
    }

    override fun findOrders(credentials: BrokerageCredentials, date: LocalDate, symbol: String, side: String): List<BrokerOrderSnapshot> =
        orderStore.values
            .filter { it.request.symbol == symbol && it.request.side == side && it.orderedAt.atZone(KST).toLocalDate() == date }
            .map {
                BrokerOrderSnapshot(
                    brokerOrderId  = it.pgOrderId,
                    brokerOrderRef = null,
                    symbol         = it.request.symbol,
                    side           = it.request.side,
                    quantity       = it.request.quantity,
                    price          = it.request.limitPrice,
                    orderedAt      = it.orderedAt,
                    status         = it.status,
                    filledQty      = it.filledQty,
                    avgFillPrice   = it.avgFillPrice,
                )
            }

    override fun cancelOrder(credentials: BrokerageCredentials, pgOrderId: String, brokerOrderRef: String?): BrokerageCancelResult {
        val order = orderStore[pgOrderId]
            ?: return BrokerageCancelResult(cancelled = false, reason = "존재하지 않는 주문입니다.")

        if (order.status != "SUBMITTED") {
            return BrokerageCancelResult(cancelled = false, reason = "이미 ${order.status} 상태라 취소할 수 없습니다.")
        }

        order.status = "CANCELLED"
        log.info("[MockKIS] 주문 취소: {}", pgOrderId)
        return BrokerageCancelResult(cancelled = true)
    }

    override fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus {
        val order = orderStore[pgOrderId]
            ?: return BrokerageOrderStatus(pgOrderId, "REJECTED", 0, null)

        // 지정가: 현재가가 지정가 이하(BUY) / 이상(SELL)이면 체결
        if (order.status == "SUBMITTED" && order.request.orderType == "LIMIT") {
            val limitPrice   = order.request.limitPrice ?: return BrokerageOrderStatus(pgOrderId, order.status, 0, null)
            val currentPrice = getCurrentPrice(order.request.symbol)
            if (currentPrice != null) {
                val shouldFill = if (order.request.side == "BUY") currentPrice <= limitPrice
                                 else currentPrice >= limitPrice
                if (shouldFill) {
                    val fee = limitPrice.multiply(BigDecimal(order.request.quantity)).multiply(FEE_RATE).setScale(0, RoundingMode.UP)
                    val tax = if (order.request.side == "SELL") limitPrice.multiply(BigDecimal(order.request.quantity)).multiply(SELL_TAX_RATE).setScale(0, RoundingMode.UP) else BigDecimal.ZERO
                    order.status       = "FILLED"
                    order.filledQty    = order.request.quantity
                    order.avgFillPrice = limitPrice
                    settlementStore[pgOrderId] = BrokerageSettlementItem(
                        pgOrderId  = pgOrderId,
                        symbol     = order.request.symbol,
                        side       = order.request.side,
                        quantity   = order.request.quantity,
                        fillPrice  = limitPrice,
                        fee        = fee,
                        tax        = tax,
                        settleDate = order.settleDate,
                    )
                    log.info("[MockKIS] 지정가 체결: {} {}주 @ {}", pgOrderId, order.request.quantity, limitPrice)
                }
            }
        }

        return BrokerageOrderStatus(
            pgOrderId    = pgOrderId,
            status       = order.status,
            filledQty    = order.filledQty,
            avgFillPrice = order.avgFillPrice,
        )
    }

    override fun getSettlements(credentials: BrokerageCredentials, date: LocalDate): List<BrokerageSettlementItem> =
        settlementStore.values.filter { it.settleDate == date }

    override fun getBalance(credentials: BrokerageCredentials): BrokerageBalance =
        BrokerageBalance(
            cash           = BigDecimal("100000000"),
            totalEvaluated = BigDecimal("100000000"),
            holdings       = emptyList(),
        )

    private fun getCurrentPrice(symbol: String): BigDecimal? =
        runCatching {
            jdbc.queryForObject(
                """SELECT c.close FROM candles_1m c
                   JOIN stocks s ON s.id = c.stock_id
                   WHERE s.symbol = ? ORDER BY c.candle_time DESC LIMIT 1""",
                BigDecimal::class.java, symbol,
            )
        }.getOrNull()

    companion object {
        private val FEE_RATE      = BigDecimal("0.00015")
        private val SELL_TAX_RATE = BigDecimal("0.0018")
        private val KST           = ZoneId.of("Asia/Seoul")
    }
}
