package com.monticker.api.paper.application

import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.matching.submit.OrderOrigin
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.paper.events.PaperAccountResetEvent
import com.monticker.api.paper.events.PaperTradeExecutedEvent
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
@Transactional
class PaperTradingService(
    private val accountRepo: PaperAccountRepository,
    private val tradeRepo: PaperTradeRepository,
    private val jdbc: JdbcTemplate,
    private val eventPublisher: ApplicationEventPublisher,
    private val projection: PortfolioPositionProjection,
    private val orderSubmitter: OrderSubmitter,
    private val conditionalService: PaperConditionalOrderService,
) {
    private fun getOrCreateAccount(userId: Long): PaperAccount =
        accountRepo.findByUserId(userId).orElseGet {
            accountRepo.save(PaperAccount(userId = userId))
        }

    /**
     * ADR-047 — 파사드. 현재가·잔고·수량 확인·기록을 직접 하지 않고 매칭 엔진에 MARKET 주문을 제출한다.
     * 리스크 게이트(@RiskChecked)가 이 경로에도 걸린다. 계좌 기록(paper_trades·포지션·정산·원장)은
     * PaperExecutionListener가 사가 트랜잭션 안에서 만든다 — 응답의 tradeId는 그 행이다.
     */
    fun buy(userId: Long, stockId: Long, quantity: Int): TradeResultResponse = execute(userId, stockId, "BUY", quantity)

    fun sell(userId: Long, stockId: Long, quantity: Int): TradeResultResponse = execute(userId, stockId, "SELL", quantity)

    private fun execute(userId: Long, stockId: Long, side: String, quantity: Int): TradeResultResponse =
        executeMarket(userId, stockId, side, quantity).first

    /** @return 응답과 매칭 엔진 주문 id(자동 등록 조건부 주문의 부모) */
    private fun executeMarket(userId: Long, stockId: Long, side: String, quantity: Int): Pair<TradeResultResponse, Long> {
        require(quantity > 0) { "수량은 1 이상이어야 합니다" }
        getOrCreateAccount(userId)
        val result = orderSubmitter.submitMarket(userId, stockId, side, quantity, OrderOrigin.MANUAL)   // ADR-085 — 화면 주문
        val trade = tradeRepo.findByFillId(result.fillId)
            ?: throw IllegalStateException("체결 기록이 없습니다: fillId=${result.fillId}")   // 리스너가 같은 트랜잭션에 만든다
        // 사가가 cash를 JDBC로 바꿨다 — 같은 트랜잭션의 JPA 1차 캐시 엔티티는 갱신 전 값이라 JDBC로 읽는다
        val cash = jdbc.query("SELECT cash FROM paper_accounts WHERE user_id = ?", { rs, _ -> rs.getBigDecimal("cash") }, userId)
            .firstOrNull() ?: BigDecimal.ZERO
        return TradeResultResponse(side, stockId, quantity, result.fillPrice, result.amount, cash, trade.id) to result.orderId
    }

    /**
     * ADR-074 — 종목 화면의 주문 패널. MARKET은 [buy]/[sell]과 같다. LIMIT은 매칭 엔진에 지정가로 제출한다:
     * 교차하면 즉시 체결(tradeId 있음), 아니면 미체결로 남고 스위퍼가 이후 시세로 체결한다(tradeId 없음, status=PENDING).
     */
    fun placeOrder(userId: Long, req: PaperOrderRequest): PaperOrderResponse {
        require(req.side == "BUY" || req.side == "SELL") { "side는 BUY 또는 SELL이어야 합니다" }
        require(req.quantity > 0) { "수량은 1 이상이어야 합니다" }
        val bracket = req.takeProfitPrice != null || req.stopLossPrice != null
        if (bracket) validateBracket(req)
        val placed = placeOrderOnly(userId, req)
        if (!bracket) return placed
        // ADR-075 "체결 시 자동 등록" — 같은 트랜잭션. 즉시 체결이면 바로 ACTIVE, 미체결 지정가면 체결 때 깨어난다.
        val parentOrderId = placed.orderId ?: throw IllegalStateException("자동 등록할 부모 주문을 찾을 수 없습니다")
        val legs = conditionalService.attachBracket(
            userId, req.stockId, req.quantity, parentOrderId, parentFilled = placed.status == "FILLED",
            takeProfitPrice = req.takeProfitPrice, stopLossPrice = req.stopLossPrice,
        )
        return placed.copy(conditionalOrderIds = legs.map { it.id })
    }

    /** 익절/손절은 매수에만 붙는다. 기준가(지정가 또는 최근 체결가)보다 익절은 위, 손절은 아래여야 한다 — 아니면 즉시 발동한다. */
    private fun validateBracket(req: PaperOrderRequest) {
        require(req.side == "BUY") { "익절/손절 자동 등록은 매수 주문에만 쓸 수 있습니다" }
        val ref = if (req.orderType == "LIMIT") req.limitPrice else
            jdbc.query("SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                { rs, _ -> rs.getBigDecimal("close") }, req.stockId).firstOrNull()
        require(ref != null) { "기준가를 알 수 없어 익절/손절을 등록할 수 없습니다" }
        req.takeProfitPrice?.let { require(it > ref) { "익절가는 기준가(${ref.toPlainString()})보다 높아야 합니다" } }
        req.stopLossPrice?.let { require(it > BigDecimal.ZERO && it < ref) { "손절가는 0보다 크고 기준가(${ref.toPlainString()})보다 낮아야 합니다" } }
    }

    private fun placeOrderOnly(userId: Long, req: PaperOrderRequest): PaperOrderResponse {
        return when (req.orderType) {
            "MARKET" -> {
                val (r, orderId) = executeMarket(userId, req.stockId, req.side, req.quantity)
                PaperOrderResponse(
                    orderId = orderId, status = "FILLED", orderType = "MARKET", side = r.side, stockId = r.stockId,
                    quantity = r.quantity, limitPrice = null, price = r.price, amount = r.amount,
                    remainingCash = r.remainingCash, tradeId = r.tradeId,
                )
            }
            "LIMIT" -> {
                val limitPrice = req.limitPrice
                require(limitPrice != null && limitPrice > BigDecimal.ZERO) { "지정가 주문에는 0보다 큰 지정가가 필요합니다" }
                require(limitPrice.stripTrailingZeros().scale() <= 4) { "지정가는 소수점 4자리까지입니다" }
                getOrCreateAccount(userId)
                val r = orderSubmitter.submitLimit(userId, req.stockId, req.side, req.quantity, limitPrice, OrderOrigin.MANUAL)
                val tradeId = r.fill?.let { f ->
                    tradeRepo.findByFillId(f.fillId)?.id ?: throw IllegalStateException("체결 기록이 없습니다: fillId=${f.fillId}")
                }
                PaperOrderResponse(
                    orderId = r.orderId, status = r.status, orderType = "LIMIT", side = r.side, stockId = r.stockId,
                    quantity = r.quantity, limitPrice = r.limitPrice, price = r.fill?.fillPrice, amount = r.fill?.amount,
                    remainingCash = currentCash(userId), tradeId = tradeId,
                )
            }
            else -> throw IllegalArgumentException("orderType은 MARKET 또는 LIMIT이어야 합니다")
        }
    }

    private fun currentCash(userId: Long): BigDecimal =
        jdbc.query("SELECT cash FROM paper_accounts WHERE user_id = ?", { rs, _ -> rs.getBigDecimal("cash") }, userId)
            .firstOrNull() ?: BigDecimal.ZERO

    fun reset(userId: Long) {
        // ADR-043 라이브 검증에서 발견: 미체결 BUY 주문의 예약금은 cash에서 이미 빠져 있다. 그 상태로 잔고를
        // 1,000만으로 되돌리면 나중에 취소될 때 환불이 1,000만 위에 얹혀 돈이 생긴다. 초기화 뒤 리스너로 취소해도
        // 순서가 같으므로(초기화 → 환불) 답이 아니다 — 먼저 취소하게 한다.
        val openOrders = jdbc.queryForObject(
            "SELECT count(*) FROM orders WHERE user_id = ? AND status IN ('PENDING', 'PARTIALLY_FILLED')",
            Long::class.java, userId,
        ) ?: 0L
        check(openOrders == 0L) { "미체결 주문 ${openOrders}건이 있어 초기화 불가 — 먼저 취소하세요" }

        val account = getOrCreateAccount(userId)
        conditionalService.cancelAllLive(userId)   // ADR-075 — 보유가 사라지면 익절·손절도 의미가 없다
        val previousCash = account.cash.amount
        account.reset()
        accountRepo.save(account)
        // 원장은 append-only(ADR-013) — 거래 행은 지워도 초기화 자체는 원장에 남긴다 (ADR-043 대사 불변식)
        eventPublisher.publishEvent(PaperAccountResetEvent(userId, previousCash, account.cash.amount))
        jdbc.update("DELETE FROM paper_trades WHERE user_id = ?", userId)
        projection.onReset(userId)
    }
}

data class PaperOrderRequest(
    val stockId: Long,
    val side: String,
    val orderType: String = "MARKET",
    val quantity: Int,
    val limitPrice: BigDecimal? = null,
    /** ADR-075 — 매수 체결 시 자동 등록할 익절가(OCO). */
    val takeProfitPrice: BigDecimal? = null,
    /** ADR-075 — 매수 체결 시 자동 등록할 손절가(OCO). */
    val stopLossPrice: BigDecimal? = null,
)

/** status: FILLED(즉시 체결 — tradeId 있음) | PENDING(미체결 지정가 — orderId로 취소·조회). */
data class PaperOrderResponse(
    val orderId: Long?,
    val status: String,
    val orderType: String,
    val side: String,
    val stockId: Long,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val price: BigDecimal?,
    val amount: BigDecimal?,
    val remainingCash: BigDecimal,
    val tradeId: Long?,
    /** 자동 등록된 조건부 주문 id(익절·손절). 없으면 빈 목록. */
    val conditionalOrderIds: List<Long> = emptyList(),
)

data class PortfolioResponse(val cash: BigDecimal, val totalValue: BigDecimal, val totalPnl: BigDecimal, val totalPnlRate: Double, val holdings: List<HoldingResponse>)
data class HoldingResponse(
    val stockId: Long, val symbol: String, val name: String, val quantity: Int, val avgPrice: BigDecimal,
    val currentPrice: BigDecimal, val value: BigDecimal, val pnl: BigDecimal, val pnlRate: Double,
    /** ADR-085 — 가장 최근 매수 체결의 진입 출처(MANUAL · WATCH_RULE · CONDITIONAL · STRATEGY). 판정 불가면 null. */
    val entryOrigin: String? = null,
    /** ADR-085 — 위 출처의 ref(규칙 id 등). MANUAL이면 null. */
    val entryOriginRef: Long? = null,
)
data class TradeResultResponse(val side: String, val stockId: Long, val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val remainingCash: BigDecimal, val tradeId: Long = 0)
data class TradeHistoryResponse(
    val id: Long, val side: String, val stockId: Long, val symbol: String, val name: String,
    val quantity: Int, val price: BigDecimal, val amount: BigDecimal, val tradedAt: java.time.Instant,
    /** ADR-085 진입 출처: MANUAL(직접) · WATCH_RULE · CONDITIONAL · STRATEGY. 판정할 수 없던 과거 거래는 null. */
    val source: String? = null,
    /** 출처 ref — WATCH_RULE이면 규칙 id, CONDITIONAL이면 조건부 주문 id, STRATEGY면 룰셋 id. */
    val originRef: Long? = null,
    val watchRuleId: Long? = null,
    val conditionalOrderId: Long? = null,
    val orderType: String = "MARKET",
    /** 감정 태그(EmotionType 이름)와 메모 — 거래마다 따로 조회하지 않도록 내역에 싣는다. 없으면 null. */
    val emotion: String? = null,
    val emotionMemo: String? = null,
)
