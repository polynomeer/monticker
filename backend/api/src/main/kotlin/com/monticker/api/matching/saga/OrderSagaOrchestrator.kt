package com.monticker.api.matching.saga

import com.monticker.api.common.aop.Timed
import com.monticker.api.common.domain.BestQuoteSource
import com.monticker.api.common.domain.CandleFreshness
import com.monticker.api.common.domain.LatestClose
import com.monticker.api.common.domain.Money
import com.monticker.api.common.domain.Price
import com.monticker.api.matching.application.FillQueryService
import com.monticker.api.matching.application.MatchingOrderBookService
import com.monticker.api.matching.application.SubmitOrderRequest
import com.monticker.api.matching.application.SubmitOrderResponse
import com.monticker.api.matching.domain.*
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.statemachine.OrderEvents
import com.monticker.api.matching.statemachine.OrderStateMachineService
import com.monticker.api.matching.statemachine.OrderStates
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * Order Saga 오케스트레이터.
 *
 * 주문 흐름을 단계별 트랜잭션으로 분해하고, 실패 시 보상 트랜잭션(Compensation)을 순서대로 실행한다.
 *
 * 단계:
 *   INIT → VALIDATED → CASH_RESERVED → ORDER_CREATED → ORDER_FILLED → CASH_SETTLED → COMPLETED
 *
 * 보상 순서 (역순):
 *   ORDER_FILLED → 미체결 주문이면 취소
 *   CASH_RESERVED → 예약된 현금 환불
 *   ORDER_CREATED → 주문 상태 CANCELLED로 변경
 */
@Service
class OrderSagaOrchestrator(
    private val sagaRepo: OrderSagaRepository,
    private val orderRepo: OrderRepository,
    private val fillRepo: FillRepository,
    private val fillQueryService: FillQueryService,
    private val orderBookService: MatchingOrderBookService,
    private val stateMachineService: OrderStateMachineService,
    private val eventPublisher: ApplicationEventPublisher,
    private val jdbc: JdbcTemplate,
    /** ADR-091 — 접수 시점 최우선 호가(marketdata 구현). 없거나 실패하면 호가 없이 접수한다. */
    private val bestQuoteSource: BestQuoteSource? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 미체결 SELL 주문의 잔량 합 — 매도 가능 수량에서 뺀다(ADR-074). */
        const val PENDING_SELL_QTY_SQL =
            "SELECT COALESCE(SUM(quantity - filled_qty), 0) AS qty FROM orders " +
                "WHERE user_id = ? AND stock_id = ? AND side = 'SELL' AND status IN ('PENDING', 'PARTIALLY_FILLED')"

        /** 최신 1분봉 종가와 그 시각 — 시각으로 신선도(CandleFreshness)를 판정한다. */
        const val LATEST_PRICE_SQL = "SELECT close, candle_time FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1"
    }

    @Timed("matching.saga.submit", tags = ["module=saga"])
    @Transactional
    fun execute(userId: Long, req: SubmitOrderRequest): SubmitOrderResponse {
        // ADR-091 — 엔진 지연의 시작점. 검증·현금 예약 전에 잰다.
        val submittedAt = Instant.now()
        val saga = sagaRepo.save(OrderSaga(
            userId   = userId,
            stockId  = req.stockId,
            side     = req.side,
            quantity = req.quantity,
        ))
        log.debug("[Saga:{}] 시작 userId={} stockId={} side={} qty={}",
            saga.id, userId, req.stockId, req.side, req.quantity)

        return try {
            val response = runSteps(saga, userId, req, submittedAt)
            saga.status = SagaStatus.COMPLETED
            saga.currentStep = SagaStep.COMPLETED
            saga.completedAt = Instant.now()
            sagaRepo.save(saga)
            response
        } catch (ex: Exception) {
            log.warn("[Saga:{}] 실패 step={} — 보상 트랜잭션 시작", saga.id, saga.currentStep, ex)
            compensate(saga)
            throw ex
        }
    }

    // ── 정방향 단계 ──────────────────────────────────────────────────────────

    private fun runSteps(saga: OrderSaga, userId: Long, req: SubmitOrderRequest, submittedAt: Instant): SubmitOrderResponse {
        // STEP 1: VALIDATE
        saga.currentStep = SagaStep.VALIDATED
        require(req.quantity > 0) { "수량은 1 이상이어야 합니다" }
        require(req.side in listOf("BUY", "SELL")) { "side는 BUY 또는 SELL이어야 합니다" }
        require(req.orderType in listOf("MARKET", "LIMIT")) { "orderType은 MARKET 또는 LIMIT이어야 합니다" }
        val limitPrice = req.limitPrice?.let { Price.of(it) }
        if (req.orderType == "LIMIT") require(limitPrice != null) { "LIMIT 주문에는 limit_price가 필요합니다" }
        val stockExists = jdbc.queryForObject("SELECT COUNT(*) FROM stocks WHERE id = ?", Long::class.java, req.stockId) ?: 0L
        require(stockExists > 0) { "존재하지 않는 종목: stockId=${req.stockId}" }

        // ADR-091 — 접수 시점 최우선 호가(실시간 호가만). 체결가와 비교해 슬리피지를 낸다. 실패해도 주문은 진행한다.
        val quote = bestQuoteSource?.let { src -> runCatching { src.bestQuote(req.stockId) }.getOrNull() }

        // 오래된 봉(시세 단절·장 마감 후)으로는 즉시 체결하지 않는다(CandleFreshness, ADR-074 Note).
        //  - MARKET: 거부한다. 이 검사는 현금 예약(STEP 2) 앞이라 예약·환불이 생기지 않는다.
        //  - LIMIT: 즉시 체결만 건너뛰고 미체결로 접수한다 — 시세가 다시 신선해지면 스위퍼가 체결한다.
        val latest = latestClose(req.stockId)
        val freshPrice: Price? = latest.takeIf { CandleFreshness.isFresh(it.candleTime) }?.let { Price.of(it.close) }
        if (req.orderType == "MARKET" && freshPrice == null) {
            throw IllegalStateException(
                "시세가 ${CandleFreshness.MAX_AGE.toMinutes()}분 넘게 갱신되지 않아 시장가 주문을 체결할 수 없습니다" +
                    "(마지막 시세 ${latest.candleTime}). 장중에 다시 시도하거나 지정가로 주문하세요: stockId=${req.stockId}",
            )
        }
        val estimatedPrice = limitPrice ?: freshPrice!!

        // ADR-047: 매도는 보유 수량 안에서만. 이전엔 이 확인이 구 페이퍼 경로에만 있어 매칭 엔진으로는 공매도가 됐다.
        // portfolio_positions는 paper 모듈의 프로젝션이지만 paper_accounts와 같은 수준의 JDBC 읽기다.
        // ADR-074: 미체결 SELL 지정가의 잔량은 이미 "팔기로 한" 수량이다 — 빼고 판정한다. 포지션 행을 FOR UPDATE로 잡아
        // 같은 종목의 동시 매도 제출을 직렬화한다(둘 다 같은 보유량을 보고 각자 통과하던 이중 매도 창을 닫는다).
        if (req.side == "SELL") {
            val held = jdbc.query(
                "SELECT net_qty FROM portfolio_positions WHERE user_id = ? AND stock_id = ? FOR UPDATE",
                { rs, _ -> rs.getInt("net_qty") }, userId, req.stockId,
            ).firstOrNull() ?: 0
            val pendingSell = jdbc.query(
                PENDING_SELL_QTY_SQL, { rs, _ -> rs.getInt("qty") }, userId, req.stockId,
            ).firstOrNull() ?: 0
            val available = held - pendingSell
            require(available >= req.quantity) {
                if (pendingSell > 0) "보유 수량 부족: 보유 $held, 미체결 매도 $pendingSell, 요청 ${req.quantity}"
                else "보유 수량 부족: 보유 $held, 요청 ${req.quantity}"
            }
        }

        // STEP 2: RESERVE_CASH (BUY 전용)
        saga.currentStep = SagaStep.CASH_RESERVED
        val reserveAmount: BigDecimal? = if (req.side == "BUY") {
            val toReserve = estimatedPrice.toMoney(req.quantity)
            require(reserveCash(userId, toReserve.amount)) { "잔고 부족: 필요 $toReserve" }
            saga.reservedAmount = toReserve.amount
            toReserve.amount
        } else null

        // STEP 3: CREATE_ORDER
        saga.currentStep = SagaStep.ORDER_CREATED
        val order = orderRepo.save(Order(
            userId    = userId,
            stockId   = req.stockId,
            side      = OrderSide.valueOf(req.side),
            orderType = OrderType.valueOf(req.orderType),
            quantity  = req.quantity,
            limitPrice = limitPrice,
            status    = OrderStatus.PENDING,
            // ADR-051 — 부분 유니크 인덱스(V48)가 백스톱이다. MatchingService의 사전 조회와 경합해
            // 두 스레드가 동시에 들어와도 두 번째 INSERT는 DB가 거부한다.
            idempotencyKey = req.idempotencyKey,
            // ADR-085 — 진입 출처. 미체결 지정가가 나중에 스위퍼로 체결될 때도 이 행에서 읽는다.
            origin    = req.origin.type,
            originRef = req.origin.ref,
            quoteBid  = quote?.bid,
            quoteAsk  = quote?.ask,
            quoteAt   = quote?.quotedAt,
            quoteSource = quote?.source,
            submittedAt = submittedAt,
        ))
        saga.orderId = order.id

        // STEP 4: FILL_ORDER (조건 충족 시 즉시 체결)
        saga.currentStep = SagaStep.ORDER_FILLED
        val fillPrice: Price? = when {
            freshPrice == null -> null
            req.orderType == "MARKET" -> freshPrice
            req.side == "BUY"  && limitPrice!! >= freshPrice -> freshPrice
            req.side == "SELL" && limitPrice!! <= freshPrice -> freshPrice
            else -> null
        }

        val fills = mutableListOf<com.monticker.api.matching.application.FillDto>()
        if (fillPrice != null) {
            val fillAmount = fillPrice.toMoney(req.quantity)
            val fill = fillRepo.save(Fill(
                orderId   = order.id,
                userId    = userId,
                stockId   = req.stockId,
                side      = req.side,
                quantity  = req.quantity,
                fillPrice = fillPrice,
                amount    = fillAmount,
                fee       = Money.ZERO,
            ))
            fills.add(fill.toFillDto())

            stateMachineService.transition(
                orderId      = order.id,
                currentState = OrderStates.PENDING,
                event        = OrderEvents.COMPLETE_FILL,
            )
            order.fill(req.quantity, fillPrice)
            orderRepo.save(order)

            // STEP 5: SETTLE_CASH
            saga.currentStep = SagaStep.CASH_SETTLED
            if (req.side == "BUY") {
                val refund = (reserveAmount ?: BigDecimal.ZERO) - fillAmount.amount
                if (refund > BigDecimal.ZERO) adjustCash(userId, refund)
            } else {
                adjustCash(userId, fillAmount.amount)
            }

            eventPublisher.publishEvent(OrderFilledEvent(
                orderId   = order.id,
                userId    = userId,
                stockId   = req.stockId,
                fillId    = fill.id,
                side      = req.side,
                quantity  = req.quantity,
                fillPrice = fillPrice.amount,
                amount    = fillAmount.amount,
                origin    = req.origin.type.name,
                originRef = req.origin.ref,
            ))
        } else {
            // ADR-048: 호가창은 pod 힙에 있고 api는 HPA로 여러 pod다. submit()의 매칭 결과를 버리므로 호가창은 미체결
            // LIMIT을 보관만 하고 체결시키지 않는다 — 그래서 pod마다 갈라져 있어도 오늘은 정합성 문제가 없다.
            // 호가창 체결을 실제로 구현하려면 scale-out-plan §6.8(종목 샤드별 단일 라이터)이 선행돼야 한다.
            orderBookService.submit(order)
            saga.currentStep = SagaStep.CASH_SETTLED  // 미체결은 정산 없음; 단계 진행
        }

        return SubmitOrderResponse(
            order   = order.toOrderDto(),
            fills   = fills,
            message = if (fills.isNotEmpty()) "주문 체결 완료" else "주문 접수 완료 (미체결)",
        )
    }

    // ── 보상 트랜잭션 ─────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun compensate(saga: OrderSaga) {
        saga.status = SagaStatus.COMPENSATING
        saga.errorMessage = saga.errorMessage ?: "단계 ${saga.currentStep}에서 실패"
        runCatching { sagaRepo.save(saga) }

        try {
            when {
                saga.currentStep >= SagaStep.ORDER_FILLED -> compensateFill(saga)
                saga.currentStep >= SagaStep.ORDER_CREATED -> compensateOrder(saga)
                saga.currentStep >= SagaStep.CASH_RESERVED -> compensateCash(saga)
            }
            saga.status = SagaStatus.COMPENSATED
            saga.compensatedAt = Instant.now()
        } catch (ex: Exception) {
            log.error("[Saga:{}] 보상 트랜잭션 실패 — 수동 검토 필요", saga.id, ex)
            saga.status = SagaStatus.FAILED
            saga.errorMessage = ex.message
        }
        sagaRepo.save(saga)
    }

    private fun compensateFill(saga: OrderSaga) {
        val orderId = saga.orderId ?: return
        val order = orderRepo.findById(orderId).orElse(null) ?: return
        if (order.status == OrderStatus.PENDING || order.status == OrderStatus.PARTIALLY_FILLED) {
            runCatching { orderBookService.cancel(order.stockId, orderId, order.side) }
            order.cancel()
            orderRepo.save(order)
            log.info("[Saga:{}] 보상: 미체결 주문 취소 orderId={}", saga.id, orderId)
        }
    }

    private fun compensateOrder(saga: OrderSaga) {
        val orderId = saga.orderId ?: return
        val order = orderRepo.findById(orderId).orElse(null) ?: return
        if (order.status != OrderStatus.CANCELLED) {
            order.cancel()
            orderRepo.save(order)
            log.info("[Saga:{}] 보상: 주문 취소 orderId={}", saga.id, orderId)
        }
    }

    private fun compensateCash(saga: OrderSaga) {
        val reserved = saga.reservedAmount ?: return
        adjustCash(saga.userId, reserved)
        log.info("[Saga:{}] 보상: 현금 환불 userId={} amount={}", saga.id, saga.userId, reserved)
    }

    // ── 복구 스케줄러 ─────────────────────────────────────────────────────────

    /**
     * 기동 후 5분 경과한 미완료 사가를 주기적으로 탐색해 보상 트랜잭션을 재시도한다.
     * 장애/재시작 후 중간 상태 사가가 영구적으로 걸리지 않도록 방지.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    @Transactional
    fun recoverIncomplete() {
        val stale = sagaRepo.findIncomplete(Instant.now().minusSeconds(300))
        if (stale.isEmpty()) return
        log.warn("[SagaRecovery] 미완료 사가 {}건 발견 — 보상 시작", stale.size)
        stale.forEach { saga ->
            log.warn("[SagaRecovery] sagaId={} step={} status={}", saga.id, saga.currentStep, saga.status)
            compensate(saga)
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    // queryForObject는 0건이면 null이 아니라 EmptyResultDataAccessException을 던져 아래
    // "?: throw IllegalStateException"이 무력화된다(부하 테스트로 실제 확인 — 최근 캔들이
    // 없는 종목 주문이 안내 메시지 없는 500으로 샜다. PaperTradingService에 있던 동일 버그
    // 참고). query+firstOrNull은 0건이어도 예외 없이 빈 리스트를 준다.
    // 봉이 아예 없으면 MARKET·LIMIT 모두 거부한다(예전과 같음). 있으면 신선도 판정은 호출자가 한다.
    private fun latestClose(stockId: Long): LatestClose =
        jdbc.query(
            LATEST_PRICE_SQL,
            { rs, _ -> LatestClose(rs.getBigDecimal("close"), rs.getTimestamp("candle_time").toInstant()) },
            stockId,
        ).firstOrNull()?.takeIf { it.close > BigDecimal.ZERO } ?: throw IllegalStateException("현재가 조회 불가: stockId=$stockId")

    private fun ensureAccountExists(userId: Long) {
        jdbc.update(
            "INSERT INTO paper_accounts (user_id, cash, created_at, updated_at) VALUES (?, 10000000, now(), now()) ON CONFLICT (user_id) DO NOTHING",
            userId
        )
    }

    /**
     * 잔고 확인과 차감을 하나의 UPDATE로 원자화한다. 두 요청이 "잔고 확인 → 차감"을
     * 별도 문장으로 수행하면(과거 구현) 동시에 들어온 두 주문이 같은 잔고를 보고 각자
     * 통과 판정을 내려 잔고가 마이너스로 떨어질 수 있다(TOCTOU). `cash >= ?` 조건을
     * UPDATE의 WHERE 절에 넣으면 행 잠금이 걸린 시점의 최신 값으로 판정되므로,
     * 두 번째 요청은 첫 번째 요청이 커밋한 이후의 실제 잔고를 기준으로 재평가된다.
     */
    private fun reserveCash(userId: Long, amount: BigDecimal): Boolean {
        ensureAccountExists(userId)
        val updated = jdbc.update(
            "UPDATE paper_accounts SET cash = cash - ?, updated_at = now() WHERE user_id = ? AND cash >= ?",
            amount, userId, amount,
        )
        return updated > 0
    }

    private fun adjustCash(userId: Long, delta: BigDecimal) {
        jdbc.update("UPDATE paper_accounts SET cash = cash + ?, updated_at = now() WHERE user_id = ?", delta, userId)
    }

    private fun Fill.toFillDto() = com.monticker.api.matching.application.FillDto(
        id        = id,
        orderId   = orderId,
        stockId   = stockId,
        side      = side,
        quantity  = quantity,
        fillPrice = fillPrice.amount,
        amount    = amount.amount,
        fee       = fee.amount,
        filledAt  = filledAt,
    )

    private fun Order.toOrderDto() = com.monticker.api.matching.application.OrderDto(
        id           = id,
        stockId      = stockId,
        side         = side.name,
        orderType    = orderType.name,
        quantity     = quantity,
        limitPrice   = limitPrice?.amount,
        filledQty    = filledQty,
        avgFillPrice = avgFillPrice?.amount,
        status       = status.name,
        rejectReason = rejectReason,
        createdAt    = createdAt,
    )
}
