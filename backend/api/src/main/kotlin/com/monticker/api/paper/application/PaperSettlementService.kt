package com.monticker.api.paper.application

import com.monticker.api.common.calendar.KrxCalendar
import com.monticker.api.common.calendar.TradingCalendar
import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.domain.SettlementCalculator
import com.monticker.api.paper.domain.SettlementStatus
import com.monticker.api.paper.events.PaperSettlementCompletedEvent
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

data class PaperSettlementDaySummary(val date: LocalDate, val net: BigDecimal, val count: Int, val holidayName: String?)

data class PaperSettlementSummary(
    val from: LocalDate,
    val to: LocalDate,
    /** 아직 PENDING인 건의 순액(매수 -, 매도 +) */
    val pendingNet: BigDecimal,
    val settledNet: BigDecimal,
    val totalNet: BigDecimal,
    val count: Int,
    val byDate: List<PaperSettlementDaySummary>,
    val holidays: List<Pair<LocalDate, String>>,
)

@Service
class PaperSettlementService(
    private val settlementRepo: PaperSettlementRepository,
    private val accountRepo: PaperAccountRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val calendar: TradingCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 페이퍼 트레이드 체결 직후 호출 — PENDING 정산 레코드를 T+2 영업일로 예약한다.
     * BUY: 즉시 debit된 금액에서 수수료가 추가로 차감된다는 사실을 기록.
     * SELL: 정산 완료 시까지 현금이 묶이는 교육적 흐름을 표현.
     *
     * ADR-086 — 결제일은 KRX 거래일 캘린더로 센다(공휴일·연말 휴장일을 건너뛴다). 체결일은 체결 시각의 KST 날짜다
     * (예전엔 서버 기본 시간대의 "오늘"이라 UTC 서버에서 KST 오전 체결이 하루 앞당겨졌다).
     */
    @Transactional
    fun createPending(trade: PaperTrade): PaperSettlement {
        val calc = SettlementCalculator.calculate(trade.side, trade.quantity, trade.price)
        val settleDate = calendar.settlementDate(trade.tradedAt)

        val settlement = PaperSettlement(
            tradeId     = trade.id,
            userId      = trade.userId,
            stockId     = trade.stockId,
            side        = trade.side,
            quantity    = trade.quantity,
            fillPrice   = trade.price,
            grossAmount = calc.grossAmount,
            fee         = calc.fee,
            tax         = calc.tax,
            netAmount   = calc.netAmount,
            settleDate  = settleDate,
        )
        return settlementRepo.save(settlement)
    }

    /**
     * 배치 Job에서 호출 — settle_date <= today인 PENDING 정산을 SETTLED로 전환하고
     * 수수료·세금을 반영해 잔고를 조정한다.
     */
    @Transactional
    fun settle(settlement: PaperSettlement) {
        val account = accountRepo.findByUserId(settlement.userId).orElse(null)
        if (account == null) {
            log.warn("정산 대상 계정 없음: userId={}, settlementId={}", settlement.userId, settlement.id)
            settlement.fail()
            settlementRepo.save(settlement)
            return
        }

        // 수수료·세금 차감 (BUY/SELL 모두 차감 방향)
        val deduction = settlement.fee.add(settlement.tax)
        if (deduction > BigDecimal.ZERO) {
            account.debit(com.monticker.api.common.domain.Money(deduction))
        }

        settlement.settle()
        settlementRepo.save(settlement)
        accountRepo.save(account)

        eventPublisher.publishEvent(
            PaperSettlementCompletedEvent(
                userId       = settlement.userId,
                settlementId = settlement.id,
                stockId      = settlement.stockId,
                fee          = settlement.fee,
                tax          = settlement.tax,
                balanceAfter = account.cash.amount,
            )
        )

        log.info(
            "정산 완료: id={} user={} side={} qty={} fee={} tax={}",
            settlement.id, settlement.userId, settlement.side,
            settlement.quantity, settlement.fee, settlement.tax,
        )
    }

    @Transactional(readOnly = true)
    fun getSettlements(userId: Long, pageable: Pageable, status: SettlementStatus? = null): Page<PaperSettlement> =
        if (status == null) settlementRepo.findAllByUserIdOrderBySettleDateDesc(userId, pageable)
        else settlementRepo.findAllByUserIdAndStatusOrderBySettleDateDesc(userId, status, pageable)

    /**
     * 기간 [from, to](정산일 기준)의 정산 순액. 매수는 현금이 나가므로 음수, 매도는 양수다(FAILED 제외).
     * 기간을 안 주면 이번 주(KST 월~일).
     */
    @Transactional(readOnly = true)
    fun getSummary(userId: Long, from: LocalDate?, to: LocalDate?): PaperSettlementSummary {
        val today = LocalDate.now(KrxCalendar.ZONE)
        val start = from ?: today.with(java.time.DayOfWeek.MONDAY)
        val end = to ?: start.plusDays(6)
        require(!end.isBefore(start)) { "to must not be before from" }
        require(java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 366) { "range must be <= 366 days" }

        val rows = settlementRepo.sumByDateAndStatus(userId, start, end)
        val byDate = rows.filter { it.status != SettlementStatus.FAILED }.groupBy { it.settleDate }.toSortedMap().map { (date, list) ->
            PaperSettlementDaySummary(
                date = date,
                net = list.fold(BigDecimal.ZERO) { a, r -> a + r.signedNet },
                count = list.sumOf { it.count }.toInt(),
                holidayName = calendar.holidayName(date),
            )
        }
        fun net(status: SettlementStatus) = rows.filter { it.status == status }.fold(BigDecimal.ZERO) { a, r -> a + r.signedNet }
        return PaperSettlementSummary(
            from = start,
            to = end,
            pendingNet = net(SettlementStatus.PENDING),
            settledNet = net(SettlementStatus.SETTLED),
            totalNet = net(SettlementStatus.PENDING) + net(SettlementStatus.SETTLED),
            count = rows.filter { it.status != SettlementStatus.FAILED }.sumOf { it.count }.toInt(),
            byDate = byDate,
            holidays = calendar.holidaysBetween(start, end).map { it.date to it.name },
        )
    }

    @Transactional(readOnly = true)
    fun getPendingSettlements(userId: Long): List<PaperSettlement> =
        settlementRepo.findAllByUserIdAndStatus(userId, SettlementStatus.PENDING)

    @Transactional(readOnly = true)
    /** 내 거래의 정산만. 남의 거래는 없는 거래와 똑같이 null(→ 404)이다 — 내용도 존재 여부도 새지 않게. */
    fun getByTradeId(userId: Long, tradeId: Long): PaperSettlement? =
        settlementRepo.findByTradeId(tradeId)?.takeIf { it.userId == userId }
}
