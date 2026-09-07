package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.domain.BrokerageSettlementStatus
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageBalance
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.BrokerageClient
import com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry
import com.monticker.api.brokerage.infrastructure.BrokerageCredentials
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRepository
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.brokerage.infrastructure.BrokerageToken
import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.risk.application.HoldingPosition
import com.monticker.api.risk.application.PortfolioSnapshot
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.wallet.application.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

@Service
class BrokerageService(
    private val clientRegistry: BrokerageClientRegistry,
    private val accountRepo: BrokerageAccountRepository,
    private val orderRepo: BrokerageOrderRepository,
    private val settlementRepo: BrokerageSettlementRepository,
    private val ledgerService: LedgerService,
    private val riskChecker: RiskCheckerService,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 계좌 연동 ──────────────────────────────────────────────────────────────

    @Transactional
    fun connect(userId: Long, provider: BrokerageProvider, appKey: String, appSecret: String, accountNumber: String): BrokerageAccount {
        val client = clientRegistry.get(provider)
        val token = client.issueToken(appKey, appSecret)
        // ADR-026 — Toss는 계좌번호만으로 호출할 수 없고 별도 조회로 얻는 accountSeq가
        // 필요하다. KIS는 오버라이드하지 않아 항상 null.
        val accountRef = client.resolveAccountRef(token, accountNumber)

        val account = accountRepo.findByUserIdAndProviderAndAccountNumber(userId, provider, accountNumber)
            .orElseGet { BrokerageAccount(userId = userId, provider = provider, accountNumber = accountNumber) }

        // 다른 증권사/계좌로 갈아탄 경우 기존 활성 계좌는 비활성화한다 — 사용자당 활성 계좌는
        // 하나로 유지한다(주문/정산 내역은 계좌별로 그대로 남는다).
        accountRepo.findByUserIdAndIsActiveTrue(userId)
            .filter { it.id != account.id }
            .ifPresent { old -> old.isActive = false; accountRepo.save(old) }

        account.isActive = true
        account.updateToken(token.accessToken, token.expiresIn)
        // ADR-025 — appKey/appSecret도 저장한다. 토큰 발급 이후의 모든 호출도
        // appkey/appsecret(또는 client_id/secret) 헤더를 요구하므로, 여기서 버리면 이후 호출이 전부 거부된다.
        account.updateCredentials(appKey, appSecret)
        account.providerAccountRef = accountRef

        log.info("증권사 계좌 연동: userId={} provider={} accountNumber={}", userId, provider, accountNumber)
        return accountRepo.save(account)
    }

    @Transactional(readOnly = true)
    fun getAccount(userId: Long): BrokerageAccount =
        accountRepo.findByUserIdAndIsActiveTrue(userId)
            .orElseThrow { IllegalStateException("연동된 증권사 계좌가 없습니다.") }

    @Transactional(readOnly = true)
    fun getBalance(userId: Long): BrokerageBalance {
        val account = getAccount(userId)
        return clientRegistry.get(account.provider).getBalance(requireCredentials(account))
    }

    // ── 주문 ───────────────────────────────────────────────────────────────────

    @Transactional
    fun submitOrder(userId: Long, request: BrokerageOrderRequest): BrokerageOrder {
        val account = getAccount(userId)
        val client = clientRegistry.get(account.provider)
        val credentials = requireCredentials(account)
        val stockId = resolveStockId(request.symbol)

        // ADR-025 — 페이퍼 트레이딩과 동일한 사전 리스크 게이트. 증권사에 보내기 전에
        // 막는다 — 실패하면 실제 주문은 아예 나가지 않는다.
        if (stockId != null) {
            val estimatedPrice = request.limitPrice ?: currentPrice(request.symbol) ?: BigDecimal.ZERO
            val snapshot = buildPortfolioSnapshot(userId, client, credentials)
            val riskResult = riskChecker.checkBrokerageOrder(userId, stockId, request.side, request.quantity, estimatedPrice, snapshot)
            if (!riskResult.approved) {
                throw RiskLimitException(riskResult.blockedBy ?: "Unknown risk rule")
            }
        } else {
            log.warn("리스크 체크 건너뜀 — 종목을 찾을 수 없음: symbol={}", request.symbol)
        }

        val result = client.submitOrder(credentials, request)

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

        // 정산 레코드가 order_id FK를 참조하므로 체결 처리 전에 주문을 먼저 저장해 실제 ID를 확보한다.
        orderRepo.save(order)

        if (result.status == "REJECTED") {
            order.reject(result.rejectReason ?: "증권사 거부")
        } else {
            // 시장가는 즉시 체결 상태로 동기화
            val status = client.getOrderStatus(credentials, result.pgOrderId)
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
        val credentials = requireCredentials(account)
        val status  = clientRegistry.get(account.provider).getOrderStatus(credentials, order.pgOrderId ?: return order)

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
        ledgerService.recordBrokerageSettlement(
            userId       = settlement.userId,
            settlementId = settlement.id,
            symbol       = settlement.symbol,
            side         = settlement.side,
            netAmount    = settlement.netAmount,
        )
        log.info("증권사 정산 완료: id={} symbol={} side={} qty={}", settlement.id, settlement.symbol, settlement.side, settlement.quantity)
    }

    @Transactional(readOnly = true)
    fun getSettlements(userId: Long, pageable: Pageable): Page<BrokerageSettlement> =
        settlementRepo.findAllByUserIdOrderBySettleDateDesc(userId, pageable)

    @Transactional(readOnly = true)
    fun getPendingSettlements(userId: Long): List<BrokerageSettlement> =
        settlementRepo.findAllByUserIdAndStatus(userId, BrokerageSettlementStatus.PENDING)

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private fun requireCredentials(account: BrokerageAccount): BrokerageCredentials {
        if (!account.isTokenValid()) throw IllegalStateException("증권사 토큰이 만료되었습니다. 재연동이 필요합니다.")
        val appKey = account.appKey ?: throw IllegalStateException("증권사 계좌 정보가 불완전합니다. 재연동이 필요합니다.")
        val appSecret = account.appSecret ?: throw IllegalStateException("증권사 계좌 정보가 불완전합니다. 재연동이 필요합니다.")
        return BrokerageCredentials(
            token               = BrokerageToken(account.accessToken!!, 0),
            appKey              = appKey,
            appSecret           = appSecret,
            accountNumber       = account.accountNumber,
            providerAccountRef  = account.providerAccountRef,
        )
    }

    /** ADR-025 — 실거래 사전 리스크 게이트에 넘길 포트폴리오 스냅샷을 조립한다. */
    private fun buildPortfolioSnapshot(userId: Long, client: BrokerageClient, credentials: BrokerageCredentials): PortfolioSnapshot {
        val balance = client.getBalance(credentials)
        val holdings = balance.holdings.mapNotNull { h ->
            resolveStockId(h.symbol)?.let { HoldingPosition(stockId = it, qty = h.quantity) }
        }

        // brokerage_settlements는 T+2로 미래 날짜에 정산되므로 "오늘의 리스크"에는 쓸 수
        // 없다 — 오늘 체결된 주문에서 직접 현금흐름을 근사한다(페이퍼의 daily PnL과 동일한 방식).
        val dailyPnl = jdbc.queryForObject(
            """SELECT COALESCE(SUM(CASE WHEN side='SELL' THEN quantity * avg_fill_price
                                         ELSE -quantity * avg_fill_price END), 0)
               FROM brokerage_orders
               WHERE user_id = ? AND status IN ('FILLED','PARTIALLY_FILLED') AND filled_at >= current_date""",
            BigDecimal::class.java, userId,
        ) ?: BigDecimal.ZERO

        val oneHourAgo = Instant.now().minusSeconds(3600)
        val recentOrderCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM brokerage_orders WHERE user_id = ? AND submitted_at > ?",
            Long::class.java, userId, Timestamp.from(oneHourAgo),
        ) ?: 0L

        return PortfolioSnapshot(
            cash             = balance.cash,
            holdings         = holdings,
            dailyPnl         = dailyPnl,
            recentOrderCount = recentOrderCount,
        )
    }

    private fun currentPrice(symbol: String): BigDecimal? =
        runCatching {
            jdbc.queryForObject(
                """SELECT c.close FROM candles_1m c
                   JOIN stocks s ON s.id = c.stock_id
                   WHERE s.symbol = ? ORDER BY c.candle_time DESC LIMIT 1""",
                BigDecimal::class.java, symbol,
            )
        }.getOrNull()

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
            jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, symbol)
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
