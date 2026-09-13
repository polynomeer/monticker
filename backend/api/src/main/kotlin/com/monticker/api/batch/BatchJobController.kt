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
