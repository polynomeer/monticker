package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageFeeModel
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.RebalanceExecution
import com.monticker.api.brokerage.domain.RebalanceExecutionLeg
import com.monticker.api.brokerage.domain.RebalanceLegStatus
import com.monticker.api.brokerage.domain.RebalanceTarget
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.RebalanceExecutionLegRepository
import com.monticker.api.brokerage.infrastructure.RebalanceExecutionRepository
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.common.exception.TradingHaltedException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/** leg 가격의 출처. 실행은 시장가라 이 값으로 주문하지 않는다 — 미리보기 추정에만 쓴다. */
enum class LegPriceSource {
    /** 증권사 잔고 응답의 현재가(보유 종목) */
    BROKER_BALANCE,
    /** 최근 1분봉 종가(보유하지 않은 신규 매수 종목) */
    LAST_CANDLE,
}

data class RebalanceLegPlan(
    val stockId: Long,
    val symbol: String,
    val side: OrderSide,
    val targetWeight: BigDecimal,
    val currentWeight: BigDecimal,
    val diffPct: BigDecimal,
    val quantity: Int,
    /** 수량을 계산한 가격. 실행(시장가 주문)에는 쓰지 않는다. */
    val price: BigDecimal = BigDecimal.ZERO,
    val priceSource: LegPriceSource = LegPriceSource.LAST_CANDLE,
) {
    /** 예상 체결 금액(가격 × 수량) — 견적이 아니다. */
    val estimatedAmount: BigDecimal get() = price.multiply(BigDecimal(quantity))
    val estimatedFee: BigDecimal get() = BrokerageFeeModel.fee(estimatedAmount)
    val estimatedTax: BigDecimal get() = BrokerageFeeModel.tax(side, estimatedAmount)
}

data class RebalancePreview(
    val totalValue: BigDecimal,
    val legs: List<RebalanceLegPlan>,
) {
    /** 예상 거래비용 = 모든 leg의 수수료 + 매도 거래세. 정산과 같은 식([BrokerageFeeModel]), 미리보기 가격 기준. */
    val estimatedFee: BigDecimal get() = legs.fold(BigDecimal.ZERO) { a, l -> a + l.estimatedFee }
    val estimatedTax: BigDecimal get() = legs.fold(BigDecimal.ZERO) { a, l -> a + l.estimatedTax }
    val estimatedCost: BigDecimal get() = estimatedFee + estimatedTax
    /** 매수 leg 예상 금액 합(수수료 제외). 이 중 보유하지 않은 종목 매수가 [estimatedNewBuyAmount]. */
    val estimatedBuyAmount: BigDecimal get() = sumOf(OrderSide.BUY)
    val estimatedSellAmount: BigDecimal get() = sumOf(OrderSide.SELL)
    val estimatedNewBuyAmount: BigDecimal get() = legs
        .filter { it.side == OrderSide.BUY && it.priceSource == LegPriceSource.LAST_CANDLE }
        .fold(BigDecimal.ZERO) { a, l -> a + l.estimatedAmount }

    private fun sumOf(side: OrderSide) = legs.filter { it.side == side }.fold(BigDecimal.ZERO) { a, l -> a + l.estimatedAmount }
}

/**
 * ADR-034 — diff 계산 + 실행. preview()/execute() 둘 다 매번 최신 잔고·가격으로 diff를
 * 새로 계산한다(저장된 계획을 재사용하지 않음 — ADR-032와 같은 이유). 각 leg는 순차적으로
 * BrokerageService.submitOrder()에 그대로 위임한다 — 리스크 게이트를 우회하지 않는다.
 */
@Service
class RebalanceExecutionService(
    private val targetService: RebalanceTargetService,
    private val brokerageService: BrokerageService,
    private val executionRepo: RebalanceExecutionRepository,
    private val legRepo: RebalanceExecutionLegRepository,
    private val jdbc: JdbcTemplate,
    private val tradingHaltService: TradingHaltService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun preview(userId: Long): RebalancePreview {
        val target = targetService.get(userId) ?: throw BusinessRuleException("저장된 리밸런싱 목표가 없습니다.")
        return computePreview(userId, target)
    }

    fun execute(userId: Long): RebalanceExecution {
        val target = targetService.get(userId) ?: throw BusinessRuleException("저장된 리밸런싱 목표가 없습니다.")
        // ADR-057 — 스위치가 켜져 있으면 실행 기록을 만들지 않는다(leg마다 막힌 실패가 쌓이지 않게).
        tradingHaltService.findActive(brokerageService.getAccount(userId).provider, userId)?.let { throw it.toException() }
        val plan = computePreview(userId, target)
        if (plan.legs.isEmpty()) throw BusinessRuleException("임계값을 넘는 리밸런싱 대상이 없습니다.")

        // ADR-032와 같은 이유로 execute() 전체를 하나의 트랜잭션으로 묶지 않는다 — 뒤 leg의
        // DB 실패가 앞서 이미 브로커에 나간 leg의 기록까지 롤백해선 안 된다. 각 저장/주문
        // 호출이 각자의 트랜잭션 경계를 갖는다.
        var execution = executionRepo.save(RebalanceExecution(userId = userId, accountId = target.accountId, targetId = target.id))
        log.info("리밸런싱 실행 시작: userId={} executionId={} legs={}", userId, execution.id, plan.legs.size)

        // 매도를 먼저(현금 확보) → 매수. 각 그룹 안에서는 괴리가 큰 종목부터.
        val ordered = plan.legs.sortedWith(compareBy({ it.side != OrderSide.SELL }, { -it.diffPct.abs().toDouble() }))

        var anyFailed = false
        for ((i, leg) in ordered.withIndex()) {
            try {
                anyFailed = anyFailed or !executeLeg(execution.id, leg, userId)
            } catch (e: TradingHaltedException) {
                // ADR-057 — 실행 도중 킬 스위치가 켜졌다. 남은 leg는 시도하지 않는다(시도해도 모두 막힌다). 이 leg와 남은
                // leg 모두 주문이 나가지 않았음을 분명히 남긴다 — 매도만 되고 매수가 막혀 현금으로 남았을 수 있다.
                ordered.drop(i).forEach { saveLeg(execution.id, it, RebalanceLegStatus.FAILED, null, "실거래 주문 중단(킬 스위치) — 주문 미전송") }
                log.warn("리밸런싱 실행 중 킬 스위치 — 남은 {}건 미실행: executionId={}", ordered.size - i, execution.id)
                anyFailed = true
                break
            }
        }

        execution.complete(anyFailed)
        execution = executionRepo.save(execution)
        log.info("리밸런싱 실행 완료: userId={} executionId={} status={}", userId, execution.id, execution.status)
        return execution
    }

    fun getLegs(executionId: Long): List<RebalanceExecutionLeg> = legRepo.findAllByExecutionId(executionId)

    @Transactional(readOnly = true)
    fun getExecutions(userId: Long, pageable: Pageable): Page<RebalanceExecution> =
        executionRepo.findAllByUserIdOrderByRequestedAtDesc(userId, pageable)

    /** @return leg 성공 여부 */
    private fun executeLeg(executionId: Long, leg: RebalanceLegPlan, userId: Long): Boolean {
        return try {
            val order = brokerageService.submitOrder(
                userId,
                BrokerageOrderRequest(symbol = leg.symbol, side = leg.side.name, orderType = "MARKET", quantity = leg.quantity),
            )
            when {
                order.status == BrokerageOrderStatus.REJECTED -> {
                    saveLeg(executionId, leg, RebalanceLegStatus.FAILED, order.id, order.rejectReason)
                    log.warn("리밸런싱 leg 거부: executionId={} symbol={} reason={}", executionId, leg.symbol, order.rejectReason)
                    false
                }
                // ADR-056 — 결과 불명. 실패로 적으면 실제로 체결된 leg가 실패로 보인다. 해소는 주문 행이 따라간다.
                order.status.isUnresolved -> {
                    saveLeg(executionId, leg, RebalanceLegStatus.UNKNOWN, order.id, order.rejectReason)
                    log.warn("리밸런싱 leg 결과 확인 중: executionId={} symbol={} orderId={}", executionId, leg.symbol, order.id)
                    false
                }
                else -> {
                    saveLeg(executionId, leg, RebalanceLegStatus.EXECUTED, order.id, null)
                    true
                }
            }
        } catch (e: TradingHaltedException) {
            throw e   // execute()가 남은 leg까지 한 번에 정리한다
        } catch (e: OrderOutcomeUnknownException) {
            saveLeg(executionId, leg, RebalanceLegStatus.UNKNOWN, e.orderId, e.message)
            log.warn("리밸런싱 leg 결과 확인 중: executionId={} symbol={} orderId={}", executionId, leg.symbol, e.orderId)
            false
        } catch (e: Exception) {
            saveLeg(executionId, leg, RebalanceLegStatus.FAILED, null, e.message?.take(500) ?: "알 수 없는 오류")
            log.warn("리밸런싱 leg 실패: executionId={} symbol={} reason={}", executionId, leg.symbol, e.message)
            false
        }
    }

    private fun saveLeg(executionId: Long, leg: RebalanceLegPlan, status: RebalanceLegStatus, executedOrderId: Long?, failReason: String?) {
        legRepo.save(
            RebalanceExecutionLeg(
                executionId = executionId, stockId = leg.stockId, symbol = leg.symbol, side = leg.side,
                targetWeight = leg.targetWeight, currentWeight = leg.currentWeight, diffPct = leg.diffPct,
                quantity = leg.quantity, status = status, executedOrderId = executedOrderId, failReason = failReason,
            )
        )
    }

    private fun computePreview(userId: Long, target: RebalanceTarget): RebalancePreview {
        val weights = targetService.parseWeights(target)
        val balance = brokerageService.getBalance(userId)
        val totalValue = balance.totalEvaluated
        if (totalValue <= BigDecimal.ZERO) return RebalancePreview(totalValue, emptyList())

        val holdingsBySymbol = balance.holdings.associateBy { it.symbol }
        val symbols = weights.keys + holdingsBySymbol.keys
        val thresholdFraction = target.thresholdPct.divide(BigDecimal(100))

        val legs = symbols.mapNotNull { symbol ->
            val targetWeight = weights[symbol] ?: BigDecimal.ZERO
            val holding = holdingsBySymbol[symbol]
            val currentValue = holding?.let { it.currentPrice.multiply(BigDecimal(it.quantity)) } ?: BigDecimal.ZERO
            val currentWeight = currentValue.divide(totalValue, 6, RoundingMode.HALF_UP)
            val diffPct = targetWeight.subtract(currentWeight)
            if (diffPct.abs() < thresholdFraction) return@mapNotNull null

            val side = if (diffPct > BigDecimal.ZERO) OrderSide.BUY else OrderSide.SELL
            val priceSource = if (holding != null) LegPriceSource.BROKER_BALANCE else LegPriceSource.LAST_CANDLE
            val price = holding?.currentPrice ?: currentPrice(symbol) ?: return@mapNotNull null
            if (price <= BigDecimal.ZERO) return@mapNotNull null

            val rawQuantity = diffPct.abs().multiply(totalValue).divide(price, 0, RoundingMode.DOWN)
            // V-L5 — BigDecimal.toInt()는 예외 없이 32비트로 wrap한다. 음수 wrap은 아래
            // quantity<=0 가드가 걸러내지만, Int.MAX_VALUE를 넘는 양수 wrap은 그대로 통과해
            // 엉뚱한(대개 훨씬 작은) 수량으로 주문이 나갈 수 있다 — 넘으면 이 leg를 버린다.
            if (rawQuantity > BigDecimal(Int.MAX_VALUE)) return@mapNotNull null
            var quantity = rawQuantity.toInt()
            if (side == OrderSide.SELL) quantity = minOf(quantity, holding?.quantity ?: 0)
            if (quantity <= 0) return@mapNotNull null

            val stockId = resolveStockId(symbol) ?: return@mapNotNull null
            RebalanceLegPlan(stockId, symbol, side, targetWeight, currentWeight, diffPct, quantity, price, priceSource)
        }

        return RebalancePreview(totalValue, legs)
    }

    // BrokerageService.currentPrice()와 동일한 조회 방식 — 보유하지 않아 BrokerageBalance에
    // 가격이 없는(신규 매수 대상) 종목의 현재가를 candles_1m에서 가져온다.
    private fun currentPrice(symbol: String): BigDecimal? =
        runCatching {
            jdbc.queryForObject(
                LATEST_CLOSE_BY_SYMBOL_SQL,
                BigDecimal::class.java, symbol,
            )
        }.getOrNull()

    private fun resolveStockId(symbol: String): Long? =
        runCatching {
            jdbc.queryForObject(STOCK_ID_BY_SYMBOL_SQL, Long::class.java, symbol)
        }.getOrNull()
}
