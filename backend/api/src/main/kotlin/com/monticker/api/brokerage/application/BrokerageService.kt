package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.BrokerageSettlementStatus
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageBalance
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.BrokerageClient
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRepository
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.brokerage.infrastructure.BrokerageToken
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@Service
class BrokerageService(
    private val brokerageClient: BrokerageClient,
    private val accountRepo: BrokerageAccountRepository,
    private val orderRepo: BrokerageOrderRepository,
    private val settlementRepo: BrokerageSettlementRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 계좌 연동 ──────────────────────────────────────────────────────────────

    @Transactional
    fun connect(userId: Long, appKey: String, appSecret: String, accountNumber: String): BrokerageAccount {
        val token = brokerageClient.issueToken(appKey, appSecret)

        val account = accountRepo.findByUserIdAndIsActiveTrue(userId).orElseGet {
            BrokerageAccount(userId = userId, accountNumber = accountNumber)
        }
        account.updateToken(token.accessToken, token.expiresIn)

        log.info("증권사 계좌 연동: userId={} accountNumber={}", userId, accountNumber)
        return accountRepo.save(account)
    }

    @Transactional(readOnly = true)
    fun getAccount(userId: Long): BrokerageAccount =
        accountRepo.findByUserIdAndIsActiveTrue(userId)
            .orElseThrow { IllegalStateException("연동된 증권사 계좌가 없습니다.") }

    @Transactional(readOnly = true)
    fun getBalance(userId: Long): BrokerageBalance {
        val account = getAccount(userId)
        val token   = requireToken(account)
        return brokerageClient.getBalance(token)
    }

    // ── 주문 ───────────────────────────────────────────────────────────────────

    @Transactional
    fun submitOrder(userId: Long, request: BrokerageOrderRequest): BrokerageOrder {
        val account = getAccount(userId)
        val token   = requireToken(account)

        val result = brokerageClient.submitOrder(token, request)
        val stockId = resolveStockId(request.symbol)

        val order = BrokerageOrder(
            userId    = userId,
            accountId = account.id,
            stockId   = stockId,
            symbol    = request.symbol,
            side      = OrderSide.valueOf(request.side),
            orderType = OrderType.valueOf(request.orderType),
            quantity  = request.quantity,
            limitPrice = request.limitPrice,
            pgOrderId = result.pgOrderId,
        )

        if (result.status == "REJECTED") {
            order.reject(result.rejectReason ?: "증권사 거부")
        } else {
            // 시장가는 즉시 체결 상태로 동기화
            val status = brokerageClient.getOrderStatus(token, result.pgOrderId)
            if (status.status == "FILLED" && status.avgFillPrice != null) {
                order.fill(status.filledQty, status.avgFillPrice!!)
                createSettlementFromFill(account, order, status.avgFillPrice!!)
            }
        }

        log.info("주문 제출: userId={} symbol={} side={} qty={} type={}", userId, request.symbol, request.side, request.quantity, request.orderType)
        return orderRepo.save(order)
    }

    @Transactional
    fun syncOrderStatus(userId: Long, orderId: Long): BrokerageOrder {
        val order   = orderRepo.findById(orderId).orElseThrow { NoSuchElementException("주문 없음: $orderId") }
        require(order.userId == userId) { "접근 권한 없음" }

        if (order.status in listOf(BrokerageOrderStatus.FILLED, BrokerageOrderStatus.CANCELLED, BrokerageOrderStatus.REJECTED)) {
            return order
        }

        val account = getAccount(userId)
        val token   = requireToken(account)
        val status  = brokerageClient.getOrderStatus(token, order.pgOrderId ?: return order)

        when (status.status) {
            "FILLED" -> {
                if (status.avgFillPrice != null) {
                    order.fill(status.filledQty, status.avgFillPrice!!)
                    createSettlementFromFill(account, order, status.avgFillPrice!!)
                }
            }
            "CANCELLED" -> order.cancel()
            "REJECTED"  -> order.reject("증권사 거부")
        }

        return orderRepo.save(order)
    }

    @Transactional
    fun cancelOrder(userId: Long, orderId: Long): BrokerageOrder {
        val order = orderRepo.findById(orderId).orElseThrow { NoSuchElementException("주문 없음: $orderId") }
        require(order.userId == userId) { "접근 권한 없음" }
        require(order.status == BrokerageOrderStatus.SUBMITTED) { "취소 불가 상태: ${order.status}" }
        order.cancel()
        return orderRepo.save(order)
    }

    @Transactional(readOnly = true)
    fun getOrders(userId: Long, pageable: Pageable): Page<BrokerageOrder> =
        orderRepo.findAllByUserIdOrderBySubmittedAtDesc(userId, pageable)

    // ── 정산 ───────────────────────────────────────────────────────────────────

    /**
     * 배치 Job에서 호출 — settle_date <= today인 PENDING 정산을 SETTLED로 전환.
     */
    @Transactional
    fun settle(settlement: BrokerageSettlement) {
        settlement.settle()
        settlementRepo.save(settlement)
        log.info("증권사 정산 완료: id={} symbol={} side={} qty={}", settlement.id, settlement.symbol, settlement.side, settlement.quantity)
    }

    @Transactional(readOnly = true)
    fun getSettlements(userId: Long, pageable: Pageable): Page<BrokerageSettlement> =
        settlementRepo.findAllByUserIdOrderBySettleDateDesc(userId, pageable)

    @Transactional(readOnly = true)
    fun getPendingSettlements(userId: Long): List<BrokerageSettlement> =
        settlementRepo.findAllByUserIdAndStatus(userId, BrokerageSettlementStatus.PENDING)

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private fun requireToken(account: BrokerageAccount): BrokerageToken {
        if (!account.isTokenValid()) throw IllegalStateException("증권사 토큰이 만료되었습니다. 재연동이 필요합니다.")
        return BrokerageToken(account.accessToken!!, 0)
    }

    private fun createSettlementFromFill(account: BrokerageAccount, order: BrokerageOrder, fillPrice: BigDecimal) {
        val gross    = fillPrice.multiply(BigDecimal(order.quantity))
        val fee      = gross.multiply(BigDecimal("0.00015")).setScale(0, java.math.RoundingMode.UP)
        val tax      = if (order.side == OrderSide.SELL) gross.multiply(BigDecimal("0.0018")).setScale(0, java.math.RoundingMode.UP) else BigDecimal.ZERO
        val net      = if (order.side == OrderSide.BUY) gross.add(fee) else gross.subtract(fee).subtract(tax)
        val settle   = addBusinessDays(LocalDate.now(), 2)

        settlementRepo.save(
            BrokerageSettlement(
                userId      = account.userId,
                accountId   = account.id,
                orderId     = order.id,
                symbol      = order.symbol,
                side        = order.side.name,
                quantity    = order.quantity,
                fillPrice   = fillPrice,
                grossAmount = gross,
                fee         = fee,
                tax         = tax,
                netAmount   = net,
                settleDate  = settle,
            )
        )
    }

    private fun resolveStockId(symbol: String): Long? =
        runCatching {
            // BrokerageService is in a different module — use local jdbc would break layering,
            // so we tolerate null stockId for mock orders
            null as Long?
        }.getOrNull()

    private fun addBusinessDays(from: LocalDate, days: Int): LocalDate {
        var date = from
        var remaining = days
        while (remaining > 0) {
            date = date.plusDays(1)
            if (date.dayOfWeek.value !in 6..7) remaining--
        }
        return date
    }
}
