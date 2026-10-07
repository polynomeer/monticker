package com.monticker.api.batch

import com.monticker.api.common.cache.CacheConfig
import com.monticker.api.common.calendar.KrxCalendar
import com.monticker.api.common.calendar.TradingCalendar
import com.monticker.api.subscription.application.RenewalSchedule
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.CacheEvict
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class BatchJobScheduler(
    private val jobLauncher: JobLauncher,
    @Qualifier("regimeClassificationJob") private val regimeJob: Job,
    @Qualifier("behaviorScoreJob")        private val scoreJob: Job,
    @Qualifier("paperSettlementJob")      private val paperSettlementJob: Job,
    @Qualifier("subscriptionRenewalJob")  private val subscriptionRenewalJob: Job,
    @Qualifier("brokerageSettlementJob")  private val brokerageSettlementJob: Job,
    @Qualifier("ledgerReconciliationJob") private val ledgerReconciliationJob: Job,
    @Qualifier("paymentReconciliationJob") private val paymentReconciliationJob: Job,
    private val calendar: TradingCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 장 마감 후 18:00 KST — 전체 종목 국면 재분류
    // Job 완료 후 regime 캐시를 전체 evict한다 (당일 분류 결과가 캐시에 반영되도록).
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Seoul")
    @CacheEvict(cacheNames = [CacheConfig.REGIME], allEntries = true)
    fun runRegimeClassification() {
        log.info("Regime classification job starting...")
        runJob(regimeJob, JobParametersBuilder()
            .addString("date", LocalDate.now().toString())
            .toJobParameters())
    }

    // 매일 새벽 02:00 — 전체 유저 BehaviorScore 재산정
    // 스크리너 정렬 지표(행동 점수 기반 탭)도 함께 무효화한다.
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    @CacheEvict(cacheNames = [CacheConfig.SCREENER], allEntries = true)
    fun runBehaviorScore() {
        log.info("BehaviorScore job starting...")
        runJob(scoreJob, JobParametersBuilder()
            .addString("date", LocalDate.now().toString())
            .toJobParameters())
    }

    // **매일** 01:00 KST — 만료 예정(24시간 내) 구독 갱신 결제 시도.
    //
    // 예전에는 매월 1일이었다. 그런데 갱신은 PG 장애·불확정이면 FAILED 로 굳히지 않고 PENDING
    // 으로 남겨 다음 실행에 넘긴다(ADR-053) — 그 "다음 실행"이 한 달 뒤면 보류된 고객의 구독이
    // 한 달 밀린다. 잡은 (구독, 청구주기)에서 유도한 결정적 orderId 로 멱등하므로 매일 돌려도
    // 같은 주기를 두 번 청구하지 않는다. 리더는 `expiresAt <= now + 1일` 만 집어가므로
    // 평소 실행은 대상 0건이다 (ADR-059).
    // 시각은 RenewalSchedule 한 곳에서 정한다 — 화면의 "다음 결제일"이 같은 값으로 계산된다(ADR-083).
    @Scheduled(cron = RenewalSchedule.CRON, zone = RenewalSchedule.ZONE)
    fun runSubscriptionRenewal() {
        log.info("Subscription renewal job starting...")
        runJob(subscriptionRenewalJob, JobParametersBuilder()
            .addString("date", LocalDate.now().toString())
            .toJobParameters())
    }

    // 장 마감 후 17:00 KST — T+2 기준일 도래한 실거래 증권사 정산 처리
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Seoul")
    fun runBrokerageSettlement() {
        val today = LocalDate.now(KrxCalendar.ZONE)
        // ADR-086 — 평일 휴장일에는 결제가 일어나지 않는다. 그날로 잡힌 정산(있다면)은 다음 영업일 실행이 함께 처리한다.
        if (!calendar.isBusinessDay(today)) { log.info("Brokerage settlement skipped: {} is not a KRX business day ({})", today, calendar.holidayName(today)); return }
        log.info("Brokerage settlement job starting...")
        runJob(brokerageSettlementJob, JobParametersBuilder()
            .addString("date", today.toString())
            .toJobParameters())
    }

    // 장 마감 후 16:30 KST — T+2 기준일 도래한 페이퍼트레이딩 정산 처리
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Seoul")
    fun runPaperSettlement() {
        val today = LocalDate.now(KrxCalendar.ZONE)
        // ADR-086 — 휴장일에는 정산하지 않는다(MON-FRI cron은 공휴일을 모른다).
        if (!calendar.isBusinessDay(today)) { log.info("Paper settlement skipped: {} is not a KRX business day ({})", today, calendar.holidayName(today)); return }
        log.info("Paper settlement job starting...")
        runJob(paperSettlementJob, JobParametersBuilder()
            .addString("date", today.toString())
            .toJobParameters())
    }

    // 6시간마다 — PENDING 으로 남은 결제를 PG에 되물어 정리한다 (ADR-059).
    //
    // PG 장애 중에는 결제가 PENDING 으로 쌓이고, 그 중 일부는 **실제로 청구된** 건이다
    // (고객은 돈을 냈는데 구독이 없는 상태). 갱신 배치는 만료 예정만 보므로 이걸 대신할 수 없다.
    // runId 가 매번 달라 같은 시각 두 파드가 돌려도 양쪽 다 실행되지만, 판단이 전부 PG 재조회
    // 결과에서 나오고 확정된 건은 더 이상 PENDING 이 아니라 읽히지 않으므로 안전하다.
    @Scheduled(cron = "0 15 */6 * * *", zone = "Asia/Seoul")
    fun runPaymentReconciliation() {
        log.info("Payment reconciliation job starting...")
        runJob(paymentReconciliationJob, JobParametersBuilder()
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters())
    }

    // 장 마감 후 17:30 KST — 페이퍼(16:30)·실거래(17:00) 정산이 원장을 다 쓴 뒤, 잔고 vs 원장 대사 (ADR-043)
    @Scheduled(cron = "0 30 17 * * *", zone = "Asia/Seoul")
    fun runLedgerReconciliation() {
        log.info("Ledger reconciliation job starting...")
        runJob(ledgerReconciliationJob, JobParametersBuilder()
            .addString("date", LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString())
            .toJobParameters())
    }

    private fun runJob(job: Job, params: JobParameters) {
        try {
            val execution = jobLauncher.run(job, params)
            log.info("Job [{}] completed: status={}", job.name, execution.status)
        } catch (e: Exception) {
            log.error("Job [{}] failed: {}", job.name, e.message)
        }
    }
}
