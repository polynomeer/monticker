package com.monticker.api.batch.backfill

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.partition.PartitionHandler
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.client.RestTemplate
import java.time.LocalDate
import javax.sql.DataSource

@Configuration
class CandleBackfillJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val cbRegistry: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun candleBackfillRestTemplate(): RestTemplate = RestTemplate()

    /**
     * 백필 Job. JobParameters:
     *   stockId  (Long)  — 대상 종목
     *   fromDate (String YYYY-MM-DD) — 시작일 (기본 1년 전)
     *   toDate   (String YYYY-MM-DD) — 종료일 (기본 어제)
     *   runId    (Long)  — 중복 실행 방지용 타임스탬프
     */
    @Bean
    fun candleBackfillJob(): Job =
        JobBuilder("candleBackfillJob", jobRepository)
            .start(candleBackfillManagerStep())
            .build()

    @Bean
    fun candleBackfillManagerStep(): Step =
        StepBuilder("candleBackfillManagerStep", jobRepository)
            .partitioner("candleBackfillWorkerStep", partitioner())
            .partitionHandler(candleBackfillPartitionHandler())
            .build()

    // Partitioner는 Job 실행 시점에 파라미터를 받아야 하므로 Step-scoped bean이 필요
    // → JobConfig에서 직접 생성하지 않고 PartitionHandler에서 조립
    private fun partitioner() = org.springframework.batch.core.partition.support.Partitioner { _ ->
        // 실제 파라미터는 candleBackfillManagerStep Step 실행 시 JobParameters에서 읽음
        // 여기서는 dummy — 실제 partition은 CandleBackfillJobLauncher가 직접 호출
        emptyMap()
    }

    @Bean
    fun candleBackfillPartitionHandler(): PartitionHandler {
        val handler = TaskExecutorPartitionHandler()
        handler.setStep(candleBackfillWorkerStep())
        handler.setTaskExecutor(SimpleAsyncTaskExecutor("backfill-"))
        handler.gridSize = 4    // 최대 4개 파티션 병렬 처리
        return handler
    }

    @Bean
    fun candleBackfillWorkerStep(): Step =
        StepBuilder("candleBackfillWorkerStep", jobRepository)
            .chunk<CandleOhlcv, CandleOhlcv>(100, transactionManager)
            .reader(candleBackfillReader())
            .writer(CandleItemWriter(jdbc))
            .faultTolerant()
            .retry(Exception::class.java)
            .retryLimit(3)
            .skip(Exception::class.java)
            .skipLimit(10)
            .build()

    // Step-scoped reader: StepExecutionContext에서 파라미터를 읽는다
    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    fun candleBackfillReader(
        @org.springframework.beans.factory.annotation.Value("#{stepExecutionContext['stockId']}") stockId: Long = 0,
        @org.springframework.beans.factory.annotation.Value("#{stepExecutionContext['fromDate']}") fromDate: String = LocalDate.now().minusYears(1).toString(),
        @org.springframework.beans.factory.annotation.Value("#{stepExecutionContext['toDate']}") toDate: String = LocalDate.now().minusDays(1).toString(),
    ): YahooCandleReader {
        val symbol = jdbc.queryForObject("SELECT symbol FROM stocks WHERE id = ?", String::class.java, stockId) ?: "UNKNOWN"
        return YahooCandleReader(
            symbol         = symbol,
            stockId        = stockId,
            fromDate       = LocalDate.parse(fromDate),
            toDate         = LocalDate.parse(toDate),
            restTemplate   = candleBackfillRestTemplate(),
            objectMapper   = objectMapper,
            circuitBreaker = cbRegistry.circuitBreaker("yahooFinance"),
        )
    }
}
