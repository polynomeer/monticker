package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.KrxPriceRules
import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.ConditionalOrder
import com.monticker.api.brokerage.domain.ConditionalOrderStatus
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry
import com.monticker.api.brokerage.infrastructure.ConditionalOrderRepository
import com.monticker.api.common.exception.BusinessRuleException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    private val clientRegistry: BrokerageClientRegistry,
    private val priceFeedMonitor: PriceFeedMonitor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(userId: Long, symbol: String, side: OrderSide, quantity: Int, leg: ConditionalOrderLeg): ConditionalOrder {
        require(quantity > 0) { "수량은 0보다 커야 합니다." }
        val account = activeAccount(userId)
        val stockId = resolveStockId(symbol) ?: throw IllegalArgumentException("존재하지 않는 종목입니다: $symbol")
        requireRealtimeFeed(account, stockId, symbol)
        validateLeg(leg, stockId)

        val order = conditionalOrderRepo.save(
            ConditionalOrder(
                userId = userId, accountId = account.id, stockId = stockId, symbol = symbol,
                side = side, triggerType = leg.triggerType, triggerPrice = leg.triggerPrice,
                orderType = leg.orderType, limitPrice = leg.limitPrice, quantity = quantity,
                expiresAt = defaultExpiry(),
            )
        )
        log.info("조건부 주문 등록: userId={} symbol={} triggerType={} triggerPrice={}", userId, symbol, leg.triggerType, leg.triggerPrice)
        return order
    }

    /** ADR-032 — OCO: 두 조건 중 하나가 발동/실패하면 나머지를 자동 취소한다(ConditionalOrderEvaluator). */
    @Transactional
    fun createOco(userId: Long, symbol: String, side: OrderSide, quantity: Int, legs: List<ConditionalOrderLeg>): List<ConditionalOrder> {
        require(legs.size == 2) { "OCO는 정확히 2개의 조건이 필요합니다." }
        require(quantity > 0) { "수량은 0보다 커야 합니다." }
        val account = activeAccount(userId)
        val stockId = resolveStockId(symbol) ?: throw IllegalArgumentException("존재하지 않는 종목입니다: $symbol")
        requireRealtimeFeed(account, stockId, symbol)
        legs.forEach { validateLeg(it, stockId) }

        val groupId = UUID.randomUUID()
        val expiresAt = defaultExpiry()
        val orders = conditionalOrderRepo.saveAll(
            legs.map { leg ->
                ConditionalOrder(
                    userId = userId, accountId = account.id, stockId = stockId, symbol = symbol,
                    side = side, triggerType = leg.triggerType, triggerPrice = leg.triggerPrice,
                    orderType = leg.orderType, limitPrice = leg.limitPrice, quantity = quantity,
                    ocoGroupId = groupId, expiresAt = expiresAt,
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

    private fun validateLeg(leg: ConditionalOrderLeg, stockId: Long) {
        require(leg.triggerPrice > BigDecimal.ZERO) { "트리거 가격은 0보다 커야 합니다." }
        if (leg.orderType == OrderType.LIMIT) {
            require(leg.limitPrice != null && leg.limitPrice > BigDecimal.ZERO) { "지정가 주문에는 가격이 필요합니다." }
            // ADR-081 — 호가 단위는 발동 시점과 무관한 규칙이라 등록할 때 막는다(발동 때 거부되면 보호가 조용히 사라진다).
            // 가격제한폭은 발동하는 날의 기준가로 판정해야 하므로 여기서 보지 않는다 — 발동 시 주문 준비가 본다.
            val market = jdbc.queryForList("SELECT market FROM stocks WHERE id = ?", String::class.java, stockId).firstOrNull()
            if (KrxPriceRules.isKrxMarket(market)) {
                require(KrxPriceRules.isOnTick(leg.limitPrice)) {
                    "호가 단위에 맞지 않는 지정가입니다: ${leg.limitPrice.stripTrailingZeros().toPlainString()}원(이 가격대의 호가 단위 ${KrxPriceRules.tickSize(leg.limitPrice).toPlainString()}원)."
                }
            }
        }
    }

    /**
     * ADR-060 — 실제 돈을 움직이는 계좌는 실시세가 연결된 종목에만 조건부 주문을 걸 수 있다. 조건부 주문은 실시세 틱으로만
     * 발동하므로(ADR-055) 커버리지 밖에 만들면 영영 발동하지 않는데, 사용자는 보호받고 있다고 믿는다.
     */
    private fun requireRealtimeFeed(account: BrokerageAccount, stockId: Long, symbol: String) {
        if (clientRegistry.movesRealMoney(account.provider) && !priceFeedMonitor.isCovered(stockId)) {
            throw BusinessRuleException("$symbol 은(는) 실시간 시세가 연결돼 있지 않아 조건부 주문을 걸 수 없습니다. 실시간 시세가 있는 종목에만 걸 수 있습니다.")
        }
    }

    /** ADR-060 — ACTIVE 조건부 주문의 실시세 상태. 실제 돈을 움직이지 않는 계좌는 합성 시세로도 발동하므로 항상 LIVE다. */
    fun priceFeeds(userId: Long, orders: List<ConditionalOrder>): Map<Long, PriceFeed> {
        val active = orders.filter { it.status == ConditionalOrderStatus.ACTIVE }
        if (active.isEmpty()) return emptyMap()
        val real = accountRepo.findByUserIdAndIsActiveTrue(userId).map { clientRegistry.movesRealMoney(it.provider) }.orElse(true)
        if (!real) return active.associate { it.id to PriceFeed.LIVE }
        val byStock = priceFeedMonitor.feedStatus(active.map { it.stockId }.toSet())
        return active.associate { it.id to (byStock[it.stockId] ?: PriceFeed.NONE) }
    }

    private fun resolveStockId(symbol: String): Long? =
        runCatching {
            jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, symbol)
        }.getOrNull()

    // V-L2 — expiresAt/EXPIRED가 스키마엔 있지만 아무도 채우지 않아 조건부 주문이 영원히
    // ACTIVE로 남았다(등록 후 잊혀진 조건이 몇 달 뒤 낡은 가격 가정으로 실거래를 낼 수 있는
    // 위험 — 만료 스케줄러는 ConditionalOrderExpiryScheduler). 90일은 실제 증권사 스탑주문의
    // 통상적인 GTC 상한(예: 국내 HTS 스탑/지정가 주문 최대 유효기간)에 맞춘 기본값이다.
    private fun defaultExpiry(): Instant = Instant.now().plus(DEFAULT_EXPIRY_DAYS, ChronoUnit.DAYS)

    companion object {
        private const val DEFAULT_EXPIRY_DAYS = 90L
    }
}
