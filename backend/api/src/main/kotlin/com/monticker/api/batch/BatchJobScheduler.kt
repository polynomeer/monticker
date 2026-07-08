package com.monticker.api.batch

import com.monticker.api.common.cache.CacheConfig
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

    private fun runJob(job: Job, params: JobParameters) {
        try {
            val execution = jobLauncher.run(job, params)
            log.info("Job [{}] completed: status={}", job.name, execution.status)
        } catch (e: Exception) {
            log.error("Job [{}] failed: {}", job.name, e.message)
        }
    }
}
