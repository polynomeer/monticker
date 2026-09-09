package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.RebalanceExecution
import com.monticker.api.brokerage.domain.RebalanceExecutionLeg
import com.monticker.api.brokerage.domain.RebalanceLegStatus
import com.monticker.api.brokerage.domain.RebalanceTarget
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.RebalanceExecutionLegRepository
import com.monticker.api.brokerage.infrastructure.RebalanceExecutionRepository
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

data class RebalanceLegPlan(
    val stockId: Long,
    val symbol: String,
    val side: OrderSide,
    val targetWeight: BigDecimal,
    val currentWeight: BigDecimal,
    val diffPct: BigDecimal,
    val quantity: Int,
)

data class RebalancePreview(
    val totalValue: BigDecimal,
    val legs: List<RebalanceLegPlan>,
)

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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun preview(userId: Long): RebalancePreview {
        val target = targetService.get(userId) ?: throw IllegalStateException("저장된 리밸런싱 목표가 없습니다.")
        return computePreview(userId, target)
    }

    fun execute(userId: Long): RebalanceExecution {
        val target = targetService.get(userId) ?: throw IllegalStateException("저장된 리밸런싱 목표가 없습니다.")
        val plan = computePreview(userId, target)
        if (plan.legs.isEmpty()) throw IllegalStateException("임계값을 넘는 리밸런싱 대상이 없습니다.")

        // ADR-032와 같은 이유로 execute() 전체를 하나의 트랜잭션으로 묶지 않는다 — 뒤 leg의
        // DB 실패가 앞서 이미 브로커에 나간 leg의 기록까지 롤백해선 안 된다. 각 저장/주문
        // 호출이 각자의 트랜잭션 경계를 갖는다.
        var execution = executionRepo.save(RebalanceExecution(userId = userId, accountId = target.accountId, targetId = target.id))
        log.info("리밸런싱 실행 시작: userId={} executionId={} legs={}", userId, execution.id, plan.legs.size)

        // 매도를 먼저(현금 확보) → 매수. 각 그룹 안에서는 괴리가 큰 종목부터.
        val ordered = plan.legs.sortedWith(compareBy({ it.side != OrderSide.SELL }, { -it.diffPct.abs().toDouble() }))

        var anyFailed = false
        for (leg in ordered) {
            anyFailed = anyFailed or !executeLeg(execution.id, leg, userId)
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
            if (order.status == BrokerageOrderStatus.REJECTED) {
                saveLeg(executionId, leg, RebalanceLegStatus.FAILED, order.id, order.rejectReason)
                log.warn("리밸런싱 leg 거부: executionId={} symbol={} reason={}", executionId, leg.symbol, order.rejectReason)
                false
            } else {
                saveLeg(executionId, leg, RebalanceLegStatus.EXECUTED, order.id, null)
                true
            }
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
            val price = holding?.currentPrice ?: currentPrice(symbol) ?: return@mapNotNull null
            if (price <= BigDecimal.ZERO) return@mapNotNull null

            var quantity = diffPct.abs().multiply(totalValue).divide(price, 0, RoundingMode.DOWN).toInt()
            if (side == OrderSide.SELL) quantity = minOf(quantity, holding?.quantity ?: 0)
            if (quantity <= 0) return@mapNotNull null

            val stockId = resolveStockId(symbol) ?: return@mapNotNull null
            RebalanceLegPlan(stockId, symbol, side, targetWeight, currentWeight, diffPct, quantity)
        }

        return RebalancePreview(totalValue, legs)
    }

    // BrokerageService.currentPrice()와 동일한 조회 방식 — 보유하지 않아 BrokerageBalance에
    // 가격이 없는(신규 매수 대상) 종목의 현재가를 candles_1m에서 가져온다.
    private fun currentPrice(symbol: String): BigDecimal? =
        runCatching {
            jdbc.queryForObject(
                """SELECT c.close FROM candles_1m c
                   JOIN stocks s ON s.id = c.stock_id
                   WHERE s.symbol = ? ORDER BY c.candle_time DESC LIMIT 1""",
                BigDecimal::class.java, symbol,
            )
        }.getOrNull()

    private fun resolveStockId(symbol: String): Long? =
        runCatching {
            jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, symbol)
        }.getOrNull()
}
