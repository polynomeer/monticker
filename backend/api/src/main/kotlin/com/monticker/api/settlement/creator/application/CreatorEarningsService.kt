package com.monticker.api.settlement.creator.application

import com.monticker.api.settlement.creator.domain.*
import com.monticker.api.settlement.creator.infrastructure.CreatorEarningRepository
import com.monticker.api.settlement.creator.infrastructure.CreatorPayoutRepository
import com.monticker.api.subscription.infrastructure.pg.PgClient
import com.monticker.api.subscription.infrastructure.pg.PaymentRequest
import com.monticker.api.wallet.application.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * quant 모듈(StrategyMarketController)이 전략 구독 시 크리에이터 수익을 정산할 때 호출하는 공개 API.
 */
@NamedInterface("api")
@Service
class CreatorEarningsService(
    private val earningRepo: CreatorEarningRepository,
    private val payoutRepo: CreatorPayoutRepository,
    private val pgClient: PgClient,
    private val ledgerService: LedgerService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val CREATOR_RATE   = BigDecimal("0.70")  // 제작자 70%
        val PLATFORM_RATE  = BigDecimal("0.30")  // 플랫폼 30%
        val MIN_PAYOUT     = BigDecimal("10000") // 최소 출금액 1만원
    }

    /**
     * 전략 구독 발생 시 호출.
     * price > 0이면 PG 결제 후 creator_earnings 적립.
     * price = 0이면 결제 없이 구독 기록만 (earningId = null).
     */
    @Transactional
    fun onStrategySubscribed(
        strategyId: Long,
        creatorId: Long,
        subscriberId: Long,
        price: BigDecimal,
        strategyCode: String,
    ): Long? {
        if (price.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("무료 전략 구독: strategyId={} subscriberId={}", strategyId, subscriberId)
            return null
        }

        val result = pgClient.requestPayment(
            PaymentRequest(userId = subscriberId, planCode = "STRATEGY_$strategyCode", amount = price)
        )
        if (!result.success) {
            log.warn("전략 구독 결제 실패: strategyId={} reason={}", strategyId, result.failureReason)
            throw IllegalStateException("결제 실패: ${result.failureReason}")
        }

        val platformFee = price.multiply(PLATFORM_RATE).setScale(0, RoundingMode.UP)
        val netAmount   = price.subtract(platformFee)

        val earning = earningRepo.save(
            CreatorEarning(
                creatorId    = creatorId,
                strategyId   = strategyId,
                subscriberId = subscriberId,
                paymentId    = null, // payment_records는 SubscriptionService 흐름에서만 생성
                grossAmount  = price,
                platformFee  = platformFee,
                netAmount    = netAmount,
            )
        )

        ledgerService.recordCreatorEarning(creatorId, strategyId, netAmount, earning.id)
        log.info(
            "수익 적립: creatorId={} strategyId={} gross={} net={}",
            creatorId, strategyId, price, netAmount,
        )
        return earning.id
    }

    // ── 조회 ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun getEarnings(creatorId: Long, pageable: Pageable): Page<CreatorEarning> =
        earningRepo.findAllByCreatorIdOrderByEarnedAtDesc(creatorId, pageable)

    @Transactional(readOnly = true)
    fun getAvailableBalance(creatorId: Long): BigDecimal =
        earningRepo.sumAvailableByCreatorId(creatorId)

    @Transactional(readOnly = true)
    fun getEarningsByStrategy(creatorId: Long): List<StrategyEarningSummary> =
        earningRepo.findEarningsByStrategy(creatorId).map { row ->
            StrategyEarningSummary(
                strategyId = (row[0] as Number).toLong(),
                totalNet   = row[1] as BigDecimal,
            )
        }

    // ── 출금 ──────────────────────────────────────────────────────────────────

    @Transactional
    fun requestPayout(
        creatorId: Long,
        amount: BigDecimal,
        bankName: String,
        accountNumber: String,
        accountHolder: String,
    ): CreatorPayout {
        val available = getAvailableBalance(creatorId)
        require(amount >= MIN_PAYOUT) { "최소 출금액은 ${MIN_PAYOUT}원입니다. 요청: $amount" }
        require(available >= amount)  { "출금 가능 잔액 부족. 가능: $available, 요청: $amount" }

        return payoutRepo.save(
            CreatorPayout(
                creatorId     = creatorId,
                amount        = amount,
                bankName      = bankName,
                accountNumber = accountNumber,
                accountHolder = accountHolder,
            )
        )
    }

    @Transactional(readOnly = true)
    fun getPayouts(creatorId: Long, pageable: Pageable): Page<CreatorPayout> =
        payoutRepo.findAllByCreatorIdOrderByRequestedAtDesc(creatorId, pageable)

    /**
     * 관리자: 출금 승인 → 실제 지급 처리 후 PAID로 전환.
     * 지급 완료 시 해당 creatorId의 AVAILABLE earning을 PAID_OUT으로 전환.
     */
    @Transactional
    fun approvePayout(payoutId: Long): CreatorPayout {
        val payout = payoutRepo.findById(payoutId).orElseThrow { NoSuchElementException("출금 요청 없음: $payoutId") }
        require(payout.status == PayoutStatus.REQUESTED) { "승인 대기 상태가 아닙니다." }

        payout.approve()
        payout.markPaid()

        // 지급된 금액만큼 AVAILABLE earnings를 PAID_OUT으로 전환 (선입선출)
        var remaining = payout.amount
        val earnings = earningRepo.findAllByCreatorIdAndStatus(payout.creatorId, EarningStatus.AVAILABLE)
            .sortedBy { it.earnedAt }
        for (earning in earnings) {
            if (remaining <= BigDecimal.ZERO) break
            earning.status = EarningStatus.PAID_OUT
            earningRepo.save(earning)
            remaining = remaining.subtract(earning.netAmount)
        }

        ledgerService.recordCreatorPayoutPaid(payout.creatorId, payout.amount, payoutId)
        log.info("출금 지급 완료: payoutId={} creatorId={} amount={}", payoutId, payout.creatorId, payout.amount)
        return payoutRepo.save(payout)
    }

    @Transactional
    fun rejectPayout(payoutId: Long, reason: String): CreatorPayout {
        val payout = payoutRepo.findById(payoutId).orElseThrow { NoSuchElementException("출금 요청 없음: $payoutId") }
        require(payout.status == PayoutStatus.REQUESTED) { "승인 대기 상태가 아닙니다." }
        payout.reject(reason)
        return payoutRepo.save(payout)
    }
}

data class StrategyEarningSummary(val strategyId: Long, val totalNet: BigDecimal)
