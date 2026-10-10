package com.monticker.api.brokerage.application

import com.monticker.api.common.calendar.TradingCalendar
import com.monticker.api.common.consent.ConsentGroup
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.common.consent.ConsentSource
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageFeeModel
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
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.common.exception.ReconnectRequiredException
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
import com.monticker.api.brokerage.domain.OrderResolution
import com.monticker.api.brokerage.infrastructure.BrokerageOrderResult
import com.monticker.api.brokerage.infrastructure.SubmitOutcome
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

@Service
class BrokerageService(
    private val clientRegistry: BrokerageClientRegistry,
    private val accountRepo: BrokerageAccountRepository,
    private val orderRepo: BrokerageOrderRepository,
    private val settlementRepo: BrokerageSettlementRepository,
    private val ledgerService: LedgerService,
    private val riskChecker: RiskCheckerService,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val meterRegistry: MeterRegistry,
    private val tradingHaltService: TradingHaltService,
    private val pendingBuyQuery: PendingBuyQuery,
    private val consentService: ConsentService,
    private val outcomeNotices: OrderOutcomeNotices,
    private val priceGuard: OrderPriceGuard,
    private val calendar: TradingCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ADR-056 — 주문 제출은 트랜잭션을 셋으로 나눈다. 같은 빈 안의 자기 호출에는 @Transactional이 걸리지 않으므로
    // (ADR-011 Note) 경계를 템플릿으로 연다. REQUIRES_NEW라 호출자가 트랜잭션 안에서 불러도 각 단계가 독립 커밋된다.
    private val requiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // 재발급 직렬화 후 계좌를 다시 읽는 데만 쓴다. 단위 테스트에서는 없다(null) — 그때는 다시 읽지 않는다.
    @PersistenceContext
    private var entityManager: EntityManager? = null

    /**
     * 주문 행을 잠그는 경로(동기화 잡·대조·수동 확정·취소)는 자격증명을 **먼저** 별도 트랜잭션에서 얻는다. 재발급은 계좌 행을 쓰므로,
     * 주문 락을 쥔 채 재발급하면 "계좌 → 주문" 순서로 잠그는 주문 준비(tx1, refreshOpenBuys)와 순서가 엇갈려 교착한다(2026-10 리뷰).
     */
    private fun credentialsFor(accountId: Long): BrokerageCredentials =
        requiresNew.execute { requireCredentials(accountRepo.findById(accountId).orElseThrow()) }!!

    private class PreparedOrder(
        val orderId: Long,
        val provider: BrokerageProvider,
        val client: BrokerageClient,
        val credentials: BrokerageCredentials,
    )

    // ── 계좌 연동 ──────────────────────────────────────────────────────────────

    @Transactional
    fun connect(userId: Long, provider: BrokerageProvider, appKey: String, appSecret: String, accountNumber: String, consents: Collection<String>): BrokerageAccount {
        // ADR-068 — 위임·자금 미보관·손실 귀속 고지 동의가 없으면 증권사 호출 전에 거부한다(400). 연동이 실패하면 같은 트랜잭션이라 기록도 롤백된다.
        consentService.requireAndRecord(userId, ConsentGroup.BROKERAGE_CONNECT, consents, ConsentSource.BROKERAGE_CONNECT)
        // 주문 준비·해지와 같은 사용자 락 — 갈아타기 중에 옛 계좌로 주문이 끼어들지 않게
        jdbc.query("SELECT pg_advisory_xact_lock(?, (? % 2147483647)::int)", { _ -> }, ADVISORY_NS_ORDER, userId)
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
            .ifPresent { old ->
                // ADR-067 — 결과가 열린 주문이 없으면 옛 키도 지운다. 있으면 대조에 필요하니 비활성화만 한다(정리 잡은 후속).
                if (openOrderCount(old.id) == 0L) old.disconnect() else old.isActive = false
                accountRepo.save(old)
            }

        account.isActive = true
        account.disconnectedAt = null
        account.updateToken(token.accessToken, token.expiresIn)
        // ADR-025 — appKey/appSecret도 저장한다. 토큰 발급 이후의 모든 호출도
        // appkey/appsecret(또는 client_id/secret) 헤더를 요구하므로, 여기서 버리면 이후 호출이 전부 거부된다.
        account.updateCredentials(appKey, appSecret)
        account.providerAccountRef = accountRef
        // ADR-027 — 사용자가 직접 재연동했으니 이전 재발급 실패 기록은 의미가 없다.
        account.authFailedAt = null

        log.info("증권사 계좌 연동: userId={} provider={} accountNumber={}", userId, provider, accountNumber)
        return accountRepo.save(account)
    }

    /**
     * ADR-067 — 연동 해지. 저장된 앱키·시크릿·토큰을 지우고 계좌를 비활성화하며, 대기 중인 조건부 주문을 취소한다.
     *
     * 증권사에서의 결과가 아직 열린 주문(제출 대기·결과 불명·최근 24시간 접수)이나 발동 중인 조건부 주문이 있으면 거부한다 —
     * 그 주문을 대조·동기화하려면 이 키가 필요하다(ADR-056/061). 키를 지운 뒤에 체결되면 정산·원장이 영영 맞지 않는다.
     *
     * 주문 준비([prepareOrder])와 같은 사용자 advisory lock을 잡는다: 해지 확인과 키 삭제 사이에 새 주문이 끼어들 수 없고,
     * 대기하던 주문 준비는 해지 커밋 뒤 계좌가 없어 증권사 호출 전에 실패한다.
     */
    @Transactional
    fun disconnect(userId: Long): DisconnectResult {
        jdbc.query("SELECT pg_advisory_xact_lock(?, (? % 2147483647)::int)", { _ -> }, ADVISORY_NS_ORDER, userId)
        val account = getAccount(userId)

        // 토큰 재발급(refreshToken)과 같은 행 락 — 그쪽이 지운 키로 새 토큰을 써넣지 못하게 한다(advisory → 행 순서는 주문 준비와 같다)
        jdbc.query("SELECT id FROM brokerage_accounts WHERE id = ? FOR UPDATE", { _ -> }, account.id)
        val openOrders = openOrderCount(account.id)
        if (openOrders > 0) {
            throw BusinessRuleException("체결 여부를 증권사에서 아직 확인 중인 주문이 ${openOrders}건 있어 연동을 해지할 수 없습니다. 미체결 주문을 취소하거나 확인이 끝난 뒤 다시 시도해주세요.")
        }

        // 발동 확인을 취소보다 먼저 한다 — 평가기가 ACTIVE→TRIGGERED로 바꾼 행은 아래 취소에 걸리지 않으므로 여기서 잡는다.
        // 그 사이에 막 발동된 행은 주문 준비가 이 락을 기다렸다가 계좌 없음으로 실패한다(증권사 호출 없음, ADR-065 알림).
        val triggered = jdbc.queryForObject(
            "SELECT COUNT(*) FROM conditional_orders WHERE account_id = ? AND status = 'TRIGGERED'",
            Long::class.java, account.id,
        ) ?: 0L
        if (triggered > 0) {
            throw BusinessRuleException("지금 발동되어 주문을 내고 있는 조건부 주문이 있어 연동을 해지할 수 없습니다. 잠시 후 다시 시도해주세요.")
        }

        val cancelled = jdbc.update(
            "UPDATE conditional_orders SET status = 'CANCELLED', updated_at = now() WHERE account_id = ? AND status = 'ACTIVE'",
            account.id,
        )

        account.disconnect()
        accountRepo.save(account)
        // 키·토큰 값은 절대 로그에 남기지 않는다
        log.info("증권사 연동 해지: userId={} accountId={} provider={} cancelledConditionalOrders={}", userId, account.id, account.provider, cancelled)
        return DisconnectResult(accountId = account.id, cancelledConditionalOrders = cancelled)
    }

    /**
     * ADR-067 — 증권사에서 아직 결과가 열린 주문 수. 대조·상태 동기화가 이 계좌의 키를 써야 하는 주문이다.
     * 접수·일부 체결은 최근 24시간만 센다 — 국내 주문은 당일 유효라 그보다 오래된 것은 동기화 잡도 보지 않고 더 체결될 수 없다.
     */
    private fun openOrderCount(accountId: Long): Long = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM brokerage_orders
        WHERE account_id = ?
          AND (status IN ('PENDING_SUBMIT', 'UNKNOWN')
               OR (status IN ('SUBMITTED', 'PARTIALLY_FILLED') AND submitted_at > now() - interval '24 hours'))
        """.trimIndent(),
        Long::class.java, accountId,
    ) ?: 0L

    /**
     * ADR-068 — 사용자가 직접 실거래를 시작하는 요청(주문·조건부 주문 등록·리밸런싱 실행)은 현재 버전의 가입 필수 동의가 있어야 한다.
     * 화면 게이트만으로는 API를 직접 부르는 경로를 막지 못한다. 이미 걸어 둔 조건부 주문의 **발동**에는 적용하지 않는다 —
     * 약관이 개정됐다는 이유로 손절이 나가지 않으면 그게 더 큰 손해다.
     */
    @Transactional(readOnly = true)
    fun requireCurrentConsents(userId: Long) {
        val missing = consentService.missingRequired(userId, ConsentGroup.SIGNUP)
        if (missing.isNotEmpty()) {
            throw BusinessRuleException("약관·개인정보 처리방침 동의가 필요합니다. 화면에서 동의한 뒤 다시 시도해주세요.")
        }
    }

    @Transactional(readOnly = true)
    fun getAccount(userId: Long): BrokerageAccount =
        accountRepo.findByUserIdAndIsActiveTrue(userId)
            .orElseThrow { IllegalStateException("연동된 증권사 계좌가 없습니다.") }

    // ADR-027 — requireCredentials()가 만료된 토큰을 조용히 재발급하며 accountRepo.save()로
    // 써야 할 수 있다. readOnly=true였다면 Hibernate가 커밋 시 flush를 건너뛰어 재발급이
    // 매번 성공한 것처럼 로그만 남고 DB에는 결코 반영되지 않는 버그가 있었다(라이브 테스트로
    // 실제 확인). requireCredentials()를 호출하는 메서드는 항상 쓰기 가능한 트랜잭션이어야 한다.
    @Transactional
    fun getBalance(userId: Long): BrokerageBalance {
        val account = getAccount(userId)
        return clientRegistry.get(account.provider).getBalance(requireCredentials(account))
    }

    // ── 주문 ───────────────────────────────────────────────────────────────────

    /**
     * ADR-056 — 실거래 주문 제출. 의도를 먼저 커밋하고(tx1), 트랜잭션 밖에서 증권사를 부르고, 결과를 기록한다(tx2).
     *
     * 의도적으로 @Transactional이 아니다 — 증권사 호출 동안 DB 커넥션을 잡지 않고, 호출 전에 의도가 커밋돼 있어야
     * 크래시 지점마다 흔적이 남는다. 예외 계약:
     *  - [OrderOutcomeUnknownException]: 의도는 커밋됐고 결과를 기록하지 못했다 → 주문이 나갔을 수 있다.
     *  - 그 밖의 예외: 의도 커밋 전에 났다 → 증권사 호출은 없었다.
     *
     * @param clientOrderId 우리가 만든 주문 식별자. 조건부 주문은 결정적 값(co-<id>)을 넘긴다.
     */
    fun submitOrder(userId: Long, request: BrokerageOrderRequest, clientOrderId: String = newClientOrderId()): BrokerageOrder {
        val prepared = requiresNew.execute { prepareOrder(userId, request, clientOrderId) }!!

        val result = try {
            prepared.client.submitOrder(prepared.credentials, request, clientOrderId)
        } catch (e: Exception) {
            // 클라이언트 계약은 예외 대신 결과로 알리는 것이지만, 던졌다면 요청이 나갔는지 모른다.
            BrokerageOrderResult.indeterminate("주문 제출 중 예외: ${e.message}")
        }
        meterRegistry.counter(
            "brokerage_order_submit_total",
            "provider", prepared.provider.name, "outcome", result.outcome.name.lowercase(),
        ).increment()

        // 기록 실패는 대개 일시적이다(커넥션·락 타임아웃) — 한 번 더 시도한다. 그래도 실패하면 증권사 주문번호를
        // 로그에 남긴다: 행에는 없으므로, 대조 매칭이 모호해질 때 운영자가 찾을 수 있는 유일한 키다.
        var lastError: Exception? = null
        repeat(2) {
            try {
                return requiresNew.execute { recordSubmitResult(prepared, result) }!!
            } catch (e: Exception) { lastError = e }
        }
        log.error("주문 결과 기록 실패 — PENDING_SUBMIT으로 남아 대조 잡이 해소한다: orderId={} outcome={} pgOrderId={} brokerOrderRef={}",
            prepared.orderId, result.outcome, result.pgOrderId, result.brokerOrderRef, lastError)
        throw OrderOutcomeUnknownException(prepared.orderId, lastError!!)
    }

    /** tx1 — 검증·리스크 게이트·중복 가드를 통과하면 PENDING_SUBMIT 행을 남긴다. 여기서 난 예외는 증권사 호출 전이다. */
    private fun prepareOrder(userId: Long, request: BrokerageOrderRequest, clientOrderId: String): PreparedOrder {
        // 같은 사용자의 주문 준비를 직렬화한다 — 중복 가드의 "확인하고 넣기"가 동시 요청에 깨지지 않도록.
        jdbc.query("SELECT pg_advisory_xact_lock(?, (? % 2147483647)::int)", { _ -> }, ADVISORY_NS_ORDER, userId)

        val account = getAccount(userId)
        // ADR-057 — 킬 스위치. 자격증명 재발급(증권사 호출)·리스크 게이트·의도 기록보다 먼저 본다. 캐시하지 않는다.
        tradingHaltService.findActive(account.provider, userId)?.let { halt ->
            meterRegistry.counter("brokerage_order_blocked_by_halt_total", "scope", halt.scope.name).increment()
            throw halt.toException()
        }
        val client = clientRegistry.get(account.provider)
        if (request.orderType == "LIMIT" && request.limitPrice == null) {
            throw IllegalArgumentException("지정가 주문에는 가격이 필요합니다.")
        }
        // 등록되지 않은 종목은 리스크 게이트를 평가할 스냅샷 근거(candles/보유 비중)가 없다.
        // 예전엔 이 경우 리스크 체크를 건너뛰고 그대로 브로커에 보냈는데, 그게 곧 게이트
        // 우회 수단이었다(docs/validation-hardening-plan.md V-C1) — 건너뛰지 않고 거부한다.
        val stockId = resolveStockId(request.symbol)
            ?: throw IllegalArgumentException("등록되지 않은 종목입니다: ${request.symbol}")
        // ADR-081 — KRX 호가 단위·가격제한폭. 자격증명 재발급(증권사 인증 호출)보다도 먼저 — 입력 오류는 어떤 증권사 호출도 부르지 않는다.
        priceGuard.check(stockId, request, { client.movesRealMoney })
        val credentials = requireCredentials(account)

        // ADR-056 — 결과를 모르는 같은 종목·방향 주문이 있으면 새 주문을 받지 않는다. 이중 주문의 가장 흔한 경로는
        // "실패한 줄 알고 다시 누르기"다. 해소(보통 1~2분)되면 다시 낼 수 있다.
        orderRepo.findAllByUserIdAndSymbolAndStatusIn(userId, request.symbol, UNRESOLVED_STATUSES)
            .firstOrNull { it.side.name == request.side }
            ?.let { throw UnresolvedOrderInProgressException(request.symbol, request.side, it.id) }

        // ADR-025 — 페이퍼 트레이딩과 동일한 사전 리스크 게이트. 증권사에 보내기 전에
        // 막는다 — 실패하면 실제 주문은 아예 나가지 않는다.
        val estimatedPrice = request.limitPrice ?: currentPrice(request.symbol) ?: BigDecimal.ZERO
        val (snapshot, balance) = buildPortfolioSnapshot(userId, account, stockId, client, credentials)
        val riskResult = riskChecker.checkBrokerageOrder(userId, stockId, request.side, request.quantity, estimatedPrice, snapshot)
        if (!riskResult.approved) {
            throw RiskLimitException(riskResult.blockedBy ?: "Unknown risk rule")
        }

        // 이 행이 커밋된 뒤에만 증권사를 부른다. 그래서 "행이 없다 = 호출되지 않았다"가 성립한다(조건부 주문 리퍼의 근거).
        // PENDING_SUBMIT 행도 시간당 주문 수 리스크 룰에 바로 잡힌다.
        val order = orderRepo.save(
            BrokerageOrder(
                userId        = userId,
                accountId     = account.id,
                stockId       = stockId,
                symbol        = request.symbol,
                side          = OrderSide.valueOf(request.side),
                orderType     = OrderType.valueOf(request.orderType),
                quantity      = request.quantity,
                limitPrice    = request.limitPrice,
                status        = BrokerageOrderStatus.PENDING_SUBMIT,
                clientOrderId = clientOrderId,
                // ADR-062 — 매도는 지금 증권사가 아는 평단가를 원가로 남긴다(매도 후엔 보유가 줄거나 사라져 다시 얻을 수 없다).
                costBasisPrice = if (request.side == "SELL") costBasis(balance, request.symbol) else null,
            )
        )
        return PreparedOrder(order.id, account.provider, client, credentials)
    }

    /** tx2 — 제출 결과를 기록한다. 대조 잡이 먼저 해소했다면(호출이 매우 늦게 끝난 경우) 덮어쓰지 않는다. */
    private fun recordSubmitResult(prepared: PreparedOrder, result: BrokerageOrderResult): BrokerageOrder {
        val order = orderRepo.findWithLockById(prepared.orderId) ?: throw IllegalStateException("주문 행 없음: ${prepared.orderId}")
        // 대조 잡이 "미접수"로 확정한 뒤에 접수 응답이 도착했다 — 증권사 응답이 맞다. 거의 불가능한 순서지만(2분 유예 >
        // 호출 최악 수십 초) 틀리면 실제 주문이 거부로 보여 재주문을 부른다.
        if (order.status == BrokerageOrderStatus.REJECTED &&
            (order.resolvedBy == OrderResolution.NOT_FOUND || order.resolvedBy == OrderResolution.MANUAL) &&
            result.outcome == SubmitOutcome.ACCEPTED) {
            log.error("미접수로 확정된 주문에 접수 응답 도착 — 접수로 정정: orderId={} pgOrderId={}", order.id, result.pgOrderId)
            order.resolvedBy = null
            order.rejectReason = null
            order.markSubmitted(result.pgOrderId!!, result.brokerOrderRef)
            return orderRepo.save(order)
        }
        if (order.status != BrokerageOrderStatus.PENDING_SUBMIT) {
            log.warn("제출 결과 도착 전에 이미 해소됨: orderId={} status={} outcome={}", order.id, order.status, result.outcome)
            return order
        }
        when (result.outcome) {
            SubmitOutcome.ACCEPTED -> {
                order.markSubmitted(result.pgOrderId!!, result.brokerOrderRef)
                // 시장가는 즉시 체결 상태로 동기화. 접수는 확정됐으니 이 조회가 실패해도 SUBMITTED로 둔다(수동 동기화로 따라잡는다).
                runCatching { prepared.client.getOrderStatus(prepared.credentials, result.pgOrderId) }
                    .onSuccess { status ->
                        val account = accountRepo.findById(order.accountId).orElseThrow()
                        applyBrokerStatus(account, order, status.status, status.filledQty, status.avgFillPrice)
                    }
                    .onFailure { log.warn("접수 직후 체결 조회 실패 — SUBMITTED 유지: orderId={} reason={}", order.id, it.message) }
            }
            SubmitOutcome.REJECTED -> order.reject(result.rejectReason ?: "증권사 거부")
            SubmitOutcome.INDETERMINATE -> {
                order.markUnknown(result.rejectReason ?: "증권사 응답 없음 — 접수 여부 확인 중")
                // 사용자가 "실패했다"고 다시 누르기 전에 알린다(이중 주문 방지). 이 트랜잭션이 커밋돼야 나간다.
                outcomeNotices.unknown(order)
                log.error("주문 결과 불명: orderId={} userId={} symbol={} side={} qty={} reason={}",
                    order.id, order.userId, order.symbol, order.side, order.quantity, result.rejectReason)
            }
        }
        log.info("주문 제출: orderId={} userId={} symbol={} side={} qty={} type={} → {}",
            order.id, order.userId, order.symbol, order.side, order.quantity, order.orderType, order.status)
        return orderRepo.save(order)
    }

    /** 증권사가 알려준 상태를 주문에 반영한다. 제출 직후·수동 동기화·대조 잡이 같은 규칙을 쓴다. */
    // notifyFill=false: 결과 불명 해소 경로 — 해소 알림(outcomeNotices.resolved)이 체결을 이미 알린다.
    private fun applyBrokerStatus(
        account: BrokerageAccount, order: BrokerageOrder, status: String, filledQty: Int, avgFillPrice: BigDecimal?, notifyFill: Boolean = true,
    ) {
        when (status) {
            "FILLED" -> if (avgFillPrice != null && order.status != BrokerageOrderStatus.FILLED) {
                order.fill(filledQty, avgFillPrice)
                createSettlementFromFill(account, order, avgFillPrice)
                if (notifyFill) outcomeNotices.filled(order)   // ADR-082 — 같은 트랜잭션: 커밋돼야 나간다
            }
            "CANCELLED" -> order.cancel()
            "REJECTED"  -> order.reject("증권사 거부")
        }
    }

    // 의도적으로 @Transactional이 아니다 — 결과 불명 분기는 대조를 자기 트랜잭션(REQUIRES_NEW)에서 돌리고 결과를 다시
    // 읽어야 한다. 바깥 트랜잭션이 있으면 영속성 컨텍스트가 대조 전 엔티티를 캐시해 해소 전 상태를 돌려주고, 요청 하나가
    // 커넥션 두 개를 잡는다.
    fun syncOrderStatus(userId: Long, orderId: Long): BrokerageOrder {
        // 남의 주문은 없는 주문과 같은 404 — 400/403으로 구분하면 주문 id 존재 여부를 열거할 수 있다
        val order   = orderRepo.findById(orderId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw NoSuchElementException("주문 없음: $orderId")

        if (order.status in listOf(BrokerageOrderStatus.FILLED, BrokerageOrderStatus.CANCELLED, BrokerageOrderStatus.REJECTED)) {
            return order
        }
        // ADR-056 — 결과 불명 주문은 증권사 주문번호가 없다. 대조를 즉시 한 번 돌린다(별도 트랜잭션, 행 락).
        if (order.status.isUnresolved) {
            reconcileUnresolved(orderId)
            return orderRepo.findById(orderId).orElseThrow()
        }
        return requiresNew.execute { syncSubmitted(userId, orderId) }!!
    }

    private fun syncSubmitted(userId: Long, orderId: Long): BrokerageOrder {
        // ADR-061 — 체결을 반영하는 모든 경로가 행 락 아래서 상태를 다시 읽는다(정산 이중 생성 방지).
        val order   = orderRepo.findWithLockById(orderId) ?: throw NoSuchElementException("주문 없음: $orderId")
        val account = getAccount(userId)
        val credentials = requireCredentials(account)
        val status  = clientRegistry.get(account.provider).getOrderStatus(credentials, order.pgOrderId ?: return order)
        applyBrokerStatus(account, order, status.status, status.filledQty, status.avgFillPrice)
        return orderRepo.save(order)
    }

    /**
     * 결과 불명 주문의 관리자 수동 확정(ADR-056 Note). 대조 잡이 고르지 못한 주문(needs_review — 매칭 후보가 둘 이상)을 운영자가
     * 증권사 주문번호를 지정하거나([brokerOrderId]) 미접수로([notPlaced]) 확정한다. 그동안 그 사용자의 같은 종목·방향 주문은 막혀 있다.
     *
     * 지정한 번호는 믿지 않고 증권사 당일 주문 목록에서 다시 찾는다 — 같은 종목·방향이어야 하고, 수량이 같아야 하며, 이미 다른
     * 주문에 연결된 번호가 아니어야 한다. 행은 대조 잡과 겹치지 않게 잠그고(대기) 연다.
     */
    fun resolveManually(orderId: Long, adminId: Long?, brokerOrderId: String?, notPlaced: Boolean, note: String): BrokerageOrder {
        require(note.isNotBlank()) { "확정 사유를 입력해주세요." }
        require((brokerOrderId != null) != notPlaced) { "증권사 주문번호를 지정하거나 미접수로 확정하거나, 둘 중 하나만 하세요." }
        val accountId = jdbc.queryForList("SELECT account_id FROM brokerage_orders WHERE id = ?", Long::class.java, orderId).firstOrNull()
            ?: throw NoSuchElementException("주문 없음: $orderId")
        val credentials = if (brokerOrderId != null) credentialsFor(accountId) else null   // 주문 락 전에(credentialsFor 참고)
        return requiresNew.execute {
            val order = orderRepo.findWithLockById(orderId) ?: throw NoSuchElementException("주문 없음: $orderId")
            if (!order.status.isUnresolved) throw BusinessRuleException("결과 불명 주문이 아닙니다: #$orderId (${order.status})")
            // 막 기록된 PENDING_SUBMIT은 아직 증권사 호출 중일 수 있다 — 대조 잡과 같은 유예를 둔다(2026-10 리뷰).
            if (order.status == BrokerageOrderStatus.PENDING_SUBMIT && order.submittedAt.isAfter(Instant.now().minus(PENDING_SUBMIT_GRACE))) {
                throw BusinessRuleException("방금 제출된 주문입니다. 증권사 응답을 기다린 뒤 다시 시도해주세요.")
            }

            if (notPlaced) {
                order.reject("관리자 확인: 증권사 미접수")
            } else {
                val account = accountRepo.findById(order.accountId).orElseThrow()
                val snapshot = clientRegistry.get(account.provider).findOrders(
                    credentials!!, order.submittedAt.atZone(KST).toLocalDate(), order.symbol, order.side.name,
                )?.firstOrNull { it.brokerOrderId == brokerOrderId }
                    ?: throw BusinessRuleException("증권사 당일 ${order.symbol} ${order.side} 주문 목록에서 $brokerOrderId 를 찾을 수 없습니다(조회 실패 포함).")
                if (snapshot.quantity != order.quantity) {
                    throw BusinessRuleException("수량이 다릅니다: 주문 ${order.quantity}주, 증권사 ${snapshot.quantity}주")
                }
                val linkedElsewhere = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM brokerage_orders WHERE account_id = ? AND pg_order_id = ? AND id <> ?",
                    Long::class.java, order.accountId, brokerOrderId, order.id,
                )!! > 0
                if (linkedElsewhere) throw BusinessRuleException("$brokerOrderId 는 이미 다른 주문에 연결돼 있습니다.")
                order.markSubmitted(snapshot.brokerOrderId, snapshot.brokerOrderRef)
                applyBrokerStatus(account, order, snapshot.status, snapshot.filledQty, snapshot.avgFillPrice, notifyFill = false)
            }
            order.resolvedBy = OrderResolution.MANUAL
            order.resolvedByUser = adminId
            order.resolutionNote = note.trim()
            order.needsReview = false
            log.warn("결과 불명 주문 수동 확정: orderId={} by={} → {} (pgOrderId={}, note={})", order.id, adminId, order.status, order.pgOrderId, note)
            outcomeNotices.resolved(order)
            orderRepo.save(order)
        }!!
    }

    /** 운영 화면용 — 아직 결과를 모르는 주문(수동 검토가 필요한 것 먼저). */
    fun unresolvedOrders(): List<BrokerageOrder> =
        orderRepo.findAllByStatusInOrderByNeedsReviewDescSubmittedAtAsc(UNRESOLVED_STATUSES)

    enum class StatusSyncResult { SKIPPED, LOOKUP_FAILED, UNCHANGED, FILLED, CANCELLED, REJECTED }

    /**
     * ADR-061 — 접수된(SUBMITTED) 주문 하나의 상태를 증권사에 물어 반영한다. 동기화 잡이 부른다. 레플리카·다른 경로가 잡고 있으면 건너뛴다.
     */
    fun syncSubmittedOrder(orderId: Long, now: Instant = Instant.now()): StatusSyncResult {
        val accountId = jdbc.queryForList(
            "SELECT account_id FROM brokerage_orders WHERE id = ? AND status = 'SUBMITTED' AND pg_order_id IS NOT NULL", Long::class.java, orderId,
        ).firstOrNull() ?: return StatusSyncResult.SKIPPED.counted()
        val credentials = runCatching { credentialsFor(accountId) }.getOrElse {
            log.warn("주문 상태 동기화 자격증명 불가: orderId={} reason={}", orderId, it.message)
            markSynced(orderId, now)
            return StatusSyncResult.LOOKUP_FAILED.counted()
        }
        return requiresNew.execute {
            val locked = jdbc.queryForList(
                "SELECT id FROM brokerage_orders WHERE id = ? AND status = 'SUBMITTED' FOR UPDATE SKIP LOCKED", Long::class.java, orderId,
            )
            if (locked.isEmpty()) return@execute StatusSyncResult.SKIPPED
            val order = orderRepo.findById(orderId).orElseThrow()
            val account = accountRepo.findById(order.accountId).orElseThrow()
            order.statusSyncedAt = now
            val status = runCatching { clientRegistry.get(account.provider).getOrderStatus(credentials, order.pgOrderId!!) }
                .getOrElse {
                    log.warn("주문 상태 동기화 조회 실패: orderId={} reason={}", orderId, it.message)
                    orderRepo.save(order)
                    return@execute StatusSyncResult.LOOKUP_FAILED
                }
            applyBrokerStatus(account, order, status.status, status.filledQty, status.avgFillPrice)
            orderRepo.save(order)
            when (order.status) {
                BrokerageOrderStatus.FILLED -> StatusSyncResult.FILLED
                BrokerageOrderStatus.CANCELLED -> StatusSyncResult.CANCELLED
                BrokerageOrderStatus.REJECTED -> StatusSyncResult.REJECTED
                else -> StatusSyncResult.UNCHANGED
            }
        }!!.counted()
    }

    private fun StatusSyncResult.counted() =
        also { meterRegistry.counter("brokerage_order_status_sync_total", "result", it.name.lowercase()).increment() }

    /**
     * 확인 시각만 별도로 남긴다. 동기화가 실패(자격증명·정산 유일 제약 위반 등)해도 그 행이 잡의 맨 앞에 남아 매 주기 같은 행만
     * 재시도하며 큐를 막지 않게 한다(2026-10 리뷰).
     */
    fun markSynced(orderId: Long, now: Instant = Instant.now()) {
        jdbc.update("UPDATE brokerage_orders SET status_synced_at = ? WHERE id = ?", Timestamp.from(now), orderId)
    }

    enum class ReconcileResult { SKIPPED, LOOKUP_FAILED, WAITING, MATCHED, NOT_FOUND, AMBIGUOUS }

    /**
     * ADR-056 — PENDING_SUBMIT/UNKNOWN 주문 하나를 증권사 당일 주문 목록과 대조한다. **재주문하지 않는다.**
     * 여러 api 레플리카가 동시에 돌아도 같은 행을 두 번 처리하지 않도록 SKIP LOCKED로 가져간다.
     */
    fun reconcileUnresolved(orderId: Long, now: Instant = Instant.now()): ReconcileResult {
        val accountId = jdbc.queryForList(
            "SELECT account_id FROM brokerage_orders WHERE id = ? AND status IN ('PENDING_SUBMIT','UNKNOWN')", Long::class.java, orderId,
        ).firstOrNull() ?: return ReconcileResult.SKIPPED
        // 주문 락 전에 자격증명을 얻는다(credentialsFor 참고). 실패하면 조회 실패로 기록된다(아래 snapshots = null).
        val credentials = runCatching { credentialsFor(accountId) }
            .onFailure { log.warn("대조용 자격증명 불가: orderId={} reason={}", orderId, it.message) }
            .getOrNull()
        return reconcileWith(orderId, now, credentials)
    }

    private fun reconcileWith(orderId: Long, now: Instant, credentials: BrokerageCredentials?): ReconcileResult =
        requiresNew.execute { reconcileLocked(orderId, now, credentials) }!!.also {
            meterRegistry.counter("brokerage_order_reconcile_total", "result", it.name.lowercase()).increment()
        }

    private fun reconcileLocked(orderId: Long, now: Instant, credentials: BrokerageCredentials?): ReconcileResult {
        val locked = jdbc.queryForList(
            "SELECT id FROM brokerage_orders WHERE id = ? AND status IN ('PENDING_SUBMIT','UNKNOWN') FOR UPDATE SKIP LOCKED",
            Long::class.java, orderId,
        )
        if (locked.isEmpty()) return ReconcileResult.SKIPPED
        val order = orderRepo.findById(orderId).orElseThrow()
        // 막 기록된 PENDING_SUBMIT은 아직 증권사 호출 중일 수 있다(읽기 타임아웃 5s + 결과 기록).
        if (order.status == BrokerageOrderStatus.PENDING_SUBMIT && order.submittedAt.isAfter(now.minus(PENDING_SUBMIT_GRACE))) {
            return ReconcileResult.SKIPPED
        }

        val account = accountRepo.findById(order.accountId).orElseThrow()
        order.reconcileAttempts += 1
        val snapshots = try {
            credentials?.let {
                clientRegistry.get(account.provider).findOrders(it, order.submittedAt.atZone(KST).toLocalDate(), order.symbol, order.side.name)
            }
        } catch (e: Exception) {
            log.warn("대조용 증권사 조회 불가: orderId={} reason={}", orderId, e.message)
            null
        }
        val knownBrokerIds = jdbc.queryForList(
            "SELECT pg_order_id FROM brokerage_orders WHERE account_id = ? AND symbol = ? AND pg_order_id IS NOT NULL AND id <> ?",
            String::class.java, order.accountId, order.symbol, order.id,
        ).toSet()
        val intent = UnknownOrderMatcher.Intent(
            order.symbol, order.side.name, order.quantity,
            order.limitPrice.takeIf { order.orderType == OrderType.LIMIT }, order.submittedAt,
        )

        // 다음 대조를 뒤로 미룬다(30s·60s·120s… 상한 10분). 해소되면 의미 없어지는 값이다.
        order.nextReconcileAt = now.plus(
            Duration.ofSeconds(30L shl (order.reconcileAttempts - 1).coerceIn(0, 5)).coerceAtMost(MAX_RECONCILE_BACKOFF),
        )
        val result = when (val d = UnknownOrderMatcher.decide(intent, snapshots, knownBrokerIds, now)) {
            UnknownOrderMatcher.Decision.LookupFailed -> ReconcileResult.LOOKUP_FAILED
            UnknownOrderMatcher.Decision.Wait -> ReconcileResult.WAITING
            UnknownOrderMatcher.Decision.NotFound -> {
                order.reject("증권사 미접수 확인 (대조)")
                order.resolvedBy = OrderResolution.NOT_FOUND
                ReconcileResult.NOT_FOUND
            }
            is UnknownOrderMatcher.Decision.Ambiguous -> {
                if (!order.needsReview) {
                    outcomeNotices.needsReview(order)
                    log.error("결과 불명 주문 매칭 모호 — 수동 검토 필요: orderId={} userId={} symbol={} candidates={}",
                        order.id, order.userId, order.symbol, d.candidates)
                }
                order.needsReview = true
                ReconcileResult.AMBIGUOUS
            }
            is UnknownOrderMatcher.Decision.Matched -> {
                order.markSubmitted(d.snapshot.brokerOrderId, d.snapshot.brokerOrderRef)
                order.resolvedBy = OrderResolution.BROKER_LOOKUP
                order.needsReview = false
                applyBrokerStatus(account, order, d.snapshot.status, d.snapshot.filledQty, d.snapshot.avgFillPrice, notifyFill = false)
                ReconcileResult.MATCHED
            }
        }
        orderRepo.save(order)
        if (result == ReconcileResult.MATCHED || result == ReconcileResult.NOT_FOUND) {
            outcomeNotices.resolved(order)
            log.info("결과 불명 주문 해소: orderId={} → {} (pgOrderId={}, attempts={})",
                order.id, order.status, order.pgOrderId, order.reconcileAttempts)
        }
        return result
    }

    // ADR-028 — 지금까지 여기서 로컬 상태만 CANCELLED로 바꾸고 증권사에는 취소 요청을 전혀
    // 보내지 않았다. 화면상 "취소됨"으로 보여도 실제로는 증권사에서 그대로 체결될 수 있었다.
    //
    // 2026-10 리뷰 — 체결을 반영하는 다른 경로(동기화 잡 등)와 같은 행 락 아래서 상태를 다시 읽는다. 예전엔 잠그지 않아, 동기화 잡이
    // 부분 체결을 FILLED+정산으로 기록하는 사이 취소가 CANCELLED와 빈 체결 컬럼으로 덮어써 "정산은 있는데 취소된 주문"이 생길 수
    // 있었다. 자격증명은 주문 락 전에 얻는다(credentialsFor 참고). 증권사 취소가 성공하면 상태를 다시 물어 취소 전 체결분을 반영한다.
    fun cancelOrder(userId: Long, orderId: Long): BrokerageOrder {
        val account = getAccount(userId)
        val credentials = credentialsFor(account.id)
        return requiresNew.execute {
            val order = orderRepo.findWithLockById(orderId)?.takeIf { it.userId == userId }
                ?: throw NoSuchElementException("주문 없음: $orderId")   // 남의 주문도 같은 404(id 열거 방지)
            require(order.status == BrokerageOrderStatus.SUBMITTED) { "취소 불가 상태: ${order.status}" }

            val client = clientRegistry.get(account.provider)
            val pgOrderId = order.pgOrderId ?: throw BusinessRuleException("증권사 주문번호가 없어 취소할 수 없습니다.")
            val result = client.cancelOrder(credentials, pgOrderId, order.brokerOrderRef)
            if (!result.cancelled) {
                throw BusinessRuleException("증권사에서 주문 취소가 불가능합니다: ${result.reason ?: "사유 없음"}")
            }

            // 잔량은 취소됐다. 취소 전에 일부라도 체결됐으면 그 체결분을 기록한다(부분 체결 후 취소 → FILLED, filledQty < quantity).
            val after = runCatching { client.getOrderStatus(credentials, pgOrderId) }.getOrNull()
            if (after != null && after.status == "FILLED" && after.avgFillPrice != null && after.filledQty > 0) {
                applyBrokerStatus(account, order, after.status, after.filledQty, after.avgFillPrice)
            } else {
                order.cancel()
            }
            log.info("주문 취소: userId={} orderId={} pgOrderId={} → {}", userId, orderId, pgOrderId, order.status)
            orderRepo.save(order)
        }!!
    }

    @Transactional(readOnly = true)
    fun getOrders(userId: Long, pageable: Pageable): Page<BrokerageOrder> =
        orderRepo.findAllByUserIdOrderBySubmittedAtDesc(userId, pageable)

    @Transactional(readOnly = true)
    fun getActiveOrdersForSymbol(userId: Long, symbol: String): List<BrokerageOrder> =
        orderRepo.findAllByUserIdAndSymbolAndStatusIn(
            userId, symbol,
            listOf(BrokerageOrderStatus.SUBMITTED, BrokerageOrderStatus.PARTIALLY_FILLED),
        )

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
        outcomeNotices.settled(settlement)   // ADR-082
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
        val appKey = account.appKey ?: throw ReconnectRequiredException("증권사 계좌 정보가 불완전합니다. 재연동이 필요합니다.")
        val appSecret = account.appSecret ?: throw ReconnectRequiredException("증권사 계좌 정보가 불완전합니다. 재연동이 필요합니다.")

        if (!account.isTokenValid()) {
            refreshToken(account)
        }

        return BrokerageCredentials(
            token               = BrokerageToken(account.accessToken!!, 0),
            appKey              = appKey,
            appSecret           = appSecret,
            accountNumber       = account.accountNumber,
            providerAccountRef  = account.providerAccountRef,
        )
    }

    /**
     * ADR-027 — KIS/Toss 둘 다 refresh token 없이 appKey/appSecret으로 동일 엔드포인트를
     * 다시 호출해 재발급하는 구조다. appKey/appSecret 자체는 여전히 유효한, 매일 반복되는
     * 정상적인 토큰 만료를 매번 "재연동 필요" 에러로 사용자에게 떠넘기지 않고 조용히 갱신한다.
     *
     * 재발급 자체가 실패하면(앱키가 실제로 취소·변경된 경우) authFailedAt을 기록해 진짜
     * "재연동 필요" 상태로 전환한다. 5분 쿨다운 동안은 재시도하지 않는다 — 취소된 앱키로
     * 매 요청마다 브로커 인증 엔드포인트를 두드리지 않기 위해서다. 쿨다운이 지나면 다시
     * 자동 재시도한다(일시적 장애였다면 스스로 복구된다).
     */
    private fun refreshToken(account: BrokerageAccount) {
        // 같은 계좌의 재발급을 직렬화한다(2026-10 리뷰): 동시 요청 둘이 각자 재발급하면 KIS는 토큰 발급을 1분 1회로 막아 두 번째가
        // 실패하고, 그 실패가 authFailedAt을 찍어 5분간 "재연동 필요"로 잠긴다. 락을 얻은 뒤 다시 읽어 그새 재발급됐으면 그대로 쓴다.
        jdbc.query("SELECT id FROM brokerage_accounts WHERE id = ? FOR UPDATE", { _ -> }, account.id)
        entityManager?.refresh(account)
        // ADR-067 — 락을 기다리는 사이 해지됐으면 지워진 키로 토큰을 다시 써넣지 않는다
        if (!account.isActive || account.appKey == null || account.appSecret == null) {
            throw ReconnectRequiredException("연동이 해지된 계좌입니다. 다시 연동해주세요.")
        }
        if (account.isTokenValid()) return
        val recentFailure = account.authFailedAt?.isAfter(Instant.now().minusSeconds(AUTH_RETRY_COOLDOWN_SECONDS)) == true
        if (recentFailure) {
            throw ReconnectRequiredException("증권사 인증이 만료되었습니다. 앱키/시크릿을 다시 발급받아 재연동해주세요.")
        }

        try {
            // 락 뒤에 다시 읽은 키를 쓴다(그새 재연동으로 바뀌었을 수 있다)
            val token = clientRegistry.get(account.provider).issueToken(account.appKey!!, account.appSecret!!)
            account.updateToken(token.accessToken, token.expiresIn)
            account.authFailedAt = null
            accountRepo.save(account)
            log.info("증권사 토큰 자동 재발급 성공: userId={} provider={}", account.userId, account.provider)
        } catch (e: Exception) {
            account.authFailedAt = Instant.now()
            accountRepo.save(account)
            log.warn("증권사 토큰 자동 재발급 실패: userId={} provider={} reason={}", account.userId, account.provider, e.message)
            throw ReconnectRequiredException("증권사 인증이 만료되었습니다. 앱키/시크릿을 다시 발급받아 재연동해주세요.")
        }
    }

    /** ADR-025 — 실거래 사전 리스크 게이트에 넘길 포트폴리오 스냅샷을 조립한다. */
    private fun buildPortfolioSnapshot(
        userId: Long, account: BrokerageAccount, stockId: Long, client: BrokerageClient, credentials: BrokerageCredentials,
    ): Pair<PortfolioSnapshot, BrokerageBalance> {
        val balance = client.getBalance(credentials)
        // 수량 0(오늘 전량 매도) 행은 빼고, 한 종목이 여러 행(현금·신용 등)으로 오면 합친다 — 그대로 두면 보유 종목 수가
        // 부풀고, 집중도는 첫 행만 봐서 과소평가된다.
        val holdings = balance.holdings
            .filter { it.quantity > 0 }
            .mapNotNull { h -> resolveStockId(h.symbol)?.let { it to h.quantity } }
            .groupBy({ it.first }, { it.second })
            .map { (id, qtys) -> HoldingPosition(stockId = id, qty = qtys.sum()) }

        // ADR-058 — 같은 종목의 진행 중 매수 상태를 증권사에서 먼저 갱신한다. 지정가 매수는 체결돼도 자동으로 FILLED가 되지
        // 않아(대조 잡은 결과 불명만 본다) 체결분이 보유와 대기에 하루 종일 이중으로 잡힌다. 이중 계산이 문제되는 건 집중도이고
        // 집중도는 이 종목만 보므로 이 종목만 조회한다(보통 0~1건). 조회가 실패하면 SUBMITTED로 남아 대기로 센다(안전 쪽).
        refreshOpenBuys(account, stockId, client, credentials)

        // ADR-062 — 오늘(KST) 체결된 매도의 실현손익. 예전엔 SUM(매도 대금 − 매수 대금) 현금 흐름이라 매수가 손실로 잡혀, 하루 3%
        // 넘게 사면 이후 모든 실거래 매수가 막혔다. 날짜도 DB 세션(UTC) 기준이었다. 원가가 없는(이 변경 전) 매도는 뺀다.
        val todayStartKst = Timestamp.from(LocalDate.now(KST).atStartOfDay(KST).toInstant())
        val dailyPnl = jdbc.queryForObject(
            """SELECT COALESCE(SUM(filled_qty * (avg_fill_price - cost_basis_price)), 0)
               FROM brokerage_orders
               WHERE user_id = ? AND side = 'SELL' AND status IN ('FILLED','PARTIALLY_FILLED')
                 AND cost_basis_price IS NOT NULL AND avg_fill_price IS NOT NULL AND filled_at >= ?""",
            BigDecimal::class.java, userId, todayStartKst,
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
            // ADR-058 — 현금에서 빼지 않는다(매수 가능 금액은 증권사가 판정하고, Toss 현금은 이미 미체결을 뺀 값이다).
            // 진행 중 매수는 노출에 더하고, 분모는 증권사가 계산한 총평가액을 쓴다.
            pendingBuys      = pendingBuyQuery.pendingBuys(account.id),
            totalAssets      = balance.totalEvaluated,
        ) to balance
    }

    /** ADR-062 — 증권사 잔고에서 그 종목의 평단가(여러 행이면 수량 가중 평균). 보유가 없으면 null. */
    private fun costBasis(balance: BrokerageBalance, symbol: String): BigDecimal? {
        // 평단가가 0 이하(증권사가 비워 보낸 값·무상 입고 등)인 행은 원가로 쓸 수 없다 — 손익을 매도 대금 전체로 부풀린다(2026-10 리뷰).
        val rows = balance.holdings.filter { it.symbol == symbol && it.quantity > 0 && it.avgPrice > BigDecimal.ZERO }
        val qty = rows.sumOf { it.quantity }
        if (qty == 0) return null
        return rows.fold(BigDecimal.ZERO) { acc, h -> acc + h.avgPrice.multiply(BigDecimal(h.quantity)) }
            .divide(BigDecimal(qty), 4, java.math.RoundingMode.HALF_UP)
    }

    private fun refreshOpenBuys(account: BrokerageAccount, stockId: Long, client: BrokerageClient, credentials: BrokerageCredentials) {
        orderRepo.findAllByAccountIdAndStockIdAndSideAndStatusAndSubmittedAtAfter(
            account.id, stockId, OrderSide.BUY, BrokerageOrderStatus.SUBMITTED, Instant.now().minus(PendingBuyQuery.OPEN_ORDER_WINDOW),
        ).forEach { candidate ->
            // ADR-061 — 동기화 잡·사용자 동기화와 같은 행을 동시에 반영하지 않게 잠그고 다시 읽는다.
            val order = orderRepo.findWithLockById(candidate.id)?.takeIf { it.status == BrokerageOrderStatus.SUBMITTED } ?: return@forEach
            val pgOrderId = order.pgOrderId ?: return@forEach
            runCatching { client.getOrderStatus(credentials, pgOrderId) }.onSuccess { status ->
                applyBrokerStatus(account, order, status.status, status.filledQty, status.avgFillPrice)
                orderRepo.save(order)
            }
        }
    }

    private fun currentPrice(symbol: String): BigDecimal? =
        runCatching {
            jdbc.queryForObject(
                LATEST_CLOSE_BY_SYMBOL_SQL,
                BigDecimal::class.java, symbol,
            )
        }.getOrNull()

    private fun createSettlementFromFill(account: BrokerageAccount, order: BrokerageOrder, fillPrice: BigDecimal) {
        // 체결 수량 기준 — 부분 체결 후 잔량이 취소되면 FILLED지만 filledQty < quantity다(2026-10 리뷰).
        val qty      = order.filledQty.takeIf { it > 0 } ?: order.quantity
        val gross    = fillPrice.multiply(BigDecimal(qty))
        val fee      = BrokerageFeeModel.fee(gross)
        val tax      = BrokerageFeeModel.tax(order.side, gross)
        val net      = if (order.side == OrderSide.BUY) gross.add(fee) else gross.subtract(fee).subtract(tax)
        // ADR-086 — KRX 거래일 캘린더로 T+2(공휴일·연말 휴장일 건너뜀), 기준일은 KST
        val settle   = calendar.settlementDate(order.filledAt ?: Instant.now())

        settlementRepo.save(
            BrokerageSettlement(
                userId      = account.userId,
                accountId   = account.id,
                orderId     = order.id,
                symbol      = order.symbol,
                side        = order.side.name,
                quantity    = qty,
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
            jdbc.queryForObject(STOCK_ID_BY_SYMBOL_SQL, Long::class.java, symbol)
        }.getOrNull()

    companion object {
        // ADR-027 — 취소된 앱키로 매 요청마다 브로커 인증 엔드포인트를 두드리지 않기 위한 쿨다운.
        private const val AUTH_RETRY_COOLDOWN_SECONDS = 300L

        /** pg_advisory_xact_lock의 첫 번째 키 — 다른 advisory lock 사용처와 겹치지 않게 네임스페이스를 둔다. */
        private const val ADVISORY_NS_ORDER = 56_001
        private val UNRESOLVED_STATUSES = listOf(BrokerageOrderStatus.PENDING_SUBMIT, BrokerageOrderStatus.UNKNOWN)
        private val PENDING_SUBMIT_GRACE: Duration = Duration.ofSeconds(30)
        private val MAX_RECONCILE_BACKOFF: Duration = Duration.ofMinutes(10)
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** Toss clientOrderId 형식(≤36자, [a-zA-Z0-9_-])을 지킨다. */
        fun newClientOrderId(): String = "mt-" + UUID.randomUUID().toString().replace("-", "")
    }
}

/** ADR-067 — 연동 해지 결과. */
data class DisconnectResult(val accountId: Long, val cancelledConditionalOrders: Int)
