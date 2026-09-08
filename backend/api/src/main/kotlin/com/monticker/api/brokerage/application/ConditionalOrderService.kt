package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.ConditionalOrder
import com.monticker.api.brokerage.domain.ConditionalOrderStatus
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.ConditionalOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class ConditionalOrderLeg(
    val triggerType: ConditionalTriggerType,
    val triggerPrice: BigDecimal,
    val orderType: OrderType,
    val limitPrice: BigDecimal? = null,
)

/**
 * ADR-032 — 조건부 주문 CRUD. 발동/실행은 ConditionalOrderEvaluator가 별도로 담당한다
 * (이 서비스는 등록/조회/취소만 — 아직 브로커에 아무것도 보내지 않는 단계).
 */
@Service
class ConditionalOrderService(
    private val accountRepo: BrokerageAccountRepository,
    private val conditionalOrderRepo: ConditionalOrderRepository,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(userId: Long, symbol: String, side: OrderSide, quantity: Int, leg: ConditionalOrderLeg): ConditionalOrder {
        val account = activeAccount(userId)
        val stockId = resolveStockId(symbol) ?: throw IllegalArgumentException("존재하지 않는 종목입니다: $symbol")
        validateLeg(leg)

        val order = conditionalOrderRepo.save(
            ConditionalOrder(
                userId = userId, accountId = account.id, stockId = stockId, symbol = symbol,
                side = side, triggerType = leg.triggerType, triggerPrice = leg.triggerPrice,
                orderType = leg.orderType, limitPrice = leg.limitPrice, quantity = quantity,
            )
        )
        log.info("조건부 주문 등록: userId={} symbol={} triggerType={} triggerPrice={}", userId, symbol, leg.triggerType, leg.triggerPrice)
        return order
    }

    /** ADR-032 — OCO: 두 조건 중 하나가 발동/실패하면 나머지를 자동 취소한다(ConditionalOrderEvaluator). */
    @Transactional
    fun createOco(userId: Long, symbol: String, side: OrderSide, quantity: Int, legs: List<ConditionalOrderLeg>): List<ConditionalOrder> {
        require(legs.size == 2) { "OCO는 정확히 2개의 조건이 필요합니다." }
        val account = activeAccount(userId)
        val stockId = resolveStockId(symbol) ?: throw IllegalArgumentException("존재하지 않는 종목입니다: $symbol")
        legs.forEach { validateLeg(it) }

        val groupId = UUID.randomUUID()
        val orders = conditionalOrderRepo.saveAll(
            legs.map { leg ->
                ConditionalOrder(
                    userId = userId, accountId = account.id, stockId = stockId, symbol = symbol,
                    side = side, triggerType = leg.triggerType, triggerPrice = leg.triggerPrice,
                    orderType = leg.orderType, limitPrice = leg.limitPrice, quantity = quantity,
                    ocoGroupId = groupId,
                )
            }
        )
        log.info("OCO 조건부 주문 등록: userId={} symbol={} groupId={}", userId, symbol, groupId)
        return orders
    }

    @Transactional(readOnly = true)
    fun getAll(userId: Long, pageable: Pageable): Page<ConditionalOrder> =
        conditionalOrderRepo.findAllByUserIdOrderByCreatedAtDesc(userId, pageable)

    @Transactional
    fun cancel(userId: Long, id: Long): ConditionalOrder {
        val order = conditionalOrderRepo.findById(id).orElseThrow { NoSuchElementException("조건부 주문 없음: $id") }
        require(order.userId == userId) { "접근 권한 없음" }
        require(order.status == ConditionalOrderStatus.ACTIVE) { "취소 불가 상태: ${order.status}" }
        order.cancel()
        log.info("조건부 주문 취소: userId={} id={}", userId, id)
        return conditionalOrderRepo.save(order)
    }

    private fun activeAccount(userId: Long) =
        accountRepo.findByUserIdAndIsActiveTrue(userId)
            .orElseThrow { IllegalStateException("연동된 증권사 계좌가 없습니다.") }

    private fun validateLeg(leg: ConditionalOrderLeg) {
        require(leg.triggerPrice > BigDecimal.ZERO) { "트리거 가격은 0보다 커야 합니다." }
        if (leg.orderType == OrderType.LIMIT) {
            require(leg.limitPrice != null && leg.limitPrice > BigDecimal.ZERO) { "지정가 주문에는 가격이 필요합니다." }
        }
    }

    private fun resolveStockId(symbol: String): Long? =
        runCatching {
            jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, symbol)
        }.getOrNull()
}
