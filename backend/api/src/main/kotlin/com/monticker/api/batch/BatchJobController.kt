package com.monticker.api.batch

import com.monticker.api.common.aop.Audited
import com.monticker.api.common.aop.RateLimited
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@Validated
@RestController
@RequestMapping("/api/admin/batch")
@PreAuthorize("hasRole('ADMIN')")
@Audited
class BatchJobController(
    private val jobLauncher: JobLauncher,
    @Qualifier("regimeClassificationJob") private val regimeJob: Job,
    @Qualifier("behaviorScoreJob")        private val scoreJob: Job,
    @Qualifier("candleBackfillJob")       private val backfillJob: Job,
    @Qualifier("ledgerReconciliationJob") private val ledgerReconciliationJob: Job,
    @Qualifier("subscriptionRenewalJob")  private val subscriptionRenewalJob: Job,
    @Qualifier("paymentReconciliationJob") private val paymentReconciliationJob: Job,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/regime")
    fun triggerRegime(): ResponseEntity<Map<String, Any>> {
        val execution = jobLauncher.run(regimeJob, JobParametersBuilder()
            .addString("date", LocalDate.now().toString())
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters())
        return ResponseEntity.ok(mapOf("jobName" to "regimeClassificationJob", "status" to execution.status.name))
    }

    @PostMapping("/behavior-score")
    fun triggerBehaviorScore(): ResponseEntity<Map<String, Any>> {
        val execution = jobLauncher.run(scoreJob, JobParametersBuilder()
            .addString("date", LocalDate.now().toString())
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters())
        return ResponseEntity.ok(mapOf("jobName" to "behaviorScoreJob", "status" to execution.status.name))
    }

    /**
     * ADR-043 원장 대사 수동 실행 — 최초 리포트 실행, 과거 날짜 재대사용.
     * @param date 대사 기준일 (기본: 오늘 KST). 같은 날 재실행은 그날 스냅샷을 덮어쓴다.
     */
    @PostMapping("/ledger-reconciliation")
    fun triggerLedgerReconciliation(
        @RequestParam(required = false) date: String?,
    ): ResponseEntity<Map<String, Any>> {
        val asOf = date ?: LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
        val execution = jobLauncher.run(ledgerReconciliationJob, JobParametersBuilder()
            .addString("date", asOf)
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters())
        val step = execution.stepExecutions.firstOrNull()
        return ResponseEntity.ok(mapOf(
            "jobName" to "ledgerReconciliationJob",
            "date" to asOf,
            "status" to execution.status.name,
            "usersChecked" to (step?.writeCount ?: 0L),
            "skipped" to (step?.skipCount ?: 0L),
        ))
    }

    /**
     * 정기결제 갱신 수동 실행 — 매월 1일 스케줄(BatchJobScheduler)을 기다리지 않고 돌린다.
     *
     * 카오스 실험 CH-13/CH-14가 이 경로를 쓴다. PG 장애 중에 갱신을 반복 실행했을 때
     * 이중청구도 오강등도 일어나지 않는지는 실제로 배치를 여러 번 돌려봐야만 알 수 있다
     * (ADR-053). 기존 엔드포인트들과 같은 ADMIN 전용·감사 대상이다.
     *
     * 재실행이 안전한 것이 이 잡의 설계다 — orderId가 (구독, 청구주기)에서 결정적으로
     * 유도되므로 같은 주기를 몇 번 돌려도 청구는 한 번뿐이다.
     */
    @PostMapping("/subscription-renewal")
    fun triggerSubscriptionRenewal(): ResponseEntity<Map<String, Any>> {
        val execution = jobLauncher.run(subscriptionRenewalJob, JobParametersBuilder()
            .addString("date", LocalDate.now().toString())
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters())
        val step = execution.stepExecutions.firstOrNull()
        val body = mapOf(
            "jobName" to "subscriptionRenewalJob",
            "status" to execution.status.name,
            "processed" to (step?.writeCount ?: 0L),
            "skipped" to (step?.skipCount ?: 0L),
        )
        // 실패한 잡에 200을 돌려주면 안 된다. CH-13을 처음 돌렸을 때 이 엔드포인트가 200을
        // 내주는 바람에, 배치가 매번 NoSuchMethodException으로 죽고 있다는 사실이 응답만
        // 봐서는 전혀 보이지 않았다(ADR-053).
        return if (execution.status.isUnsuccessful) ResponseEntity.internalServerError().body(body)
               else ResponseEntity.ok(body)
    }

    /**
     * PENDING 결제 적체 정리 수동 실행 (ADR-059) — PG 장애 복구 직후 6시간을 기다리지 않고 돌린다.
     * 재실행이 안전하다: 판단이 전부 PG 재조회에서 나오고 확정된 건은 더 이상 읽히지 않는다.
     */
    @PostMapping("/payment-reconciliation")
    fun triggerPaymentReconciliation(): ResponseEntity<Map<String, Any>> {
        val execution = jobLauncher.run(paymentReconciliationJob, JobParametersBuilder()
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters())
        val step = execution.stepExecutions.firstOrNull()
        val body = mapOf(
            "jobName" to "paymentReconciliationJob",
            "status" to execution.status.name,
            "processed" to (step?.writeCount ?: 0L),
            "skipped" to (step?.skipCount ?: 0L),
        )
        return if (execution.status.isUnsuccessful) ResponseEntity.internalServerError().body(body)
               else ResponseEntity.ok(body)
    }

    /**
     * 특정 종목의 과거 캔들 백필.
     * @param stockId  대상 종목 ID
     * @param fromDate 시작일 (기본: 1년 전)
     * @param toDate   종료일 (기본: 어제)
     */
    @PostMapping("/candle-backfill")
    @RateLimited(limit = 5, windowSec = 3600, keyPrefix = "batch.candle_backfill")
    fun triggerCandleBackfill(
        @RequestParam stockId: Long,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
    ): ResponseEntity<Map<String, Any>> {
        val from = fromDate ?: LocalDate.now().minusYears(1).toString()
        val to   = toDate   ?: LocalDate.now().minusDays(1).toString()

        val execution = jobLauncher.run(backfillJob, JobParametersBuilder()
            .addLong("stockId", stockId)
            .addString("fromDate", from)
            .addString("toDate", to)
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters())

        return ResponseEntity.ok(mapOf(
            "jobName" to "candleBackfillJob",
            "stockId" to stockId,
            "fromDate" to from,
            "toDate" to to,
            "status" to execution.status.name,
        ))
    }
}
