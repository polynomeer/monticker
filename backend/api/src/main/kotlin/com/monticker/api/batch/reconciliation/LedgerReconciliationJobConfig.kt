package com.monticker.api.batch.reconciliation

import com.monticker.api.wallet.application.LedgerReconciliationService
import com.monticker.api.wallet.application.ReconciliationResult
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Timestamp
import java.time.LocalDate
import javax.sql.DataSource

/**
 * ADR-043 §3 — 일일 원장 대사 배치. 장 마감 후 당일 원장 이벤트가 있는 유저만 대상.
 * Job 파라미터 `date`가 JobInstance 식별자라 같은 날 두 파드가 동시에 돌려도 한쪽은
 * JobInstanceAlreadyCompleteException으로 거절된다 — 다른 배치와 같은 멀티파드 안전장치.
 */
@Configuration
class LedgerReconciliationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val reconciliation: LedgerReconciliationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun ledgerReconciliationJob(): Job =
        JobBuilder("ledgerReconciliationJob", jobRepository)
            .start(ledgerReconciliationStep())
            .build()

    @Bean
    fun ledgerReconciliationStep(): Step =
        StepBuilder("ledgerReconciliationStep", jobRepository)
            .chunk<Long, ReconciliationResult>(50, transactionManager)
            .reader(ledgerActiveUserReader(null))
            .processor(ledgerReconciliationProcessor(null))
            .writer(ledgerReconciliationWriter())
            .faultTolerant()
            .skip(Exception::class.java)   // 유저 한 명의 대사 실패가 나머지를 막지 않는다
            .skipLimit(100)
            .build()

    // 리더·프로세서는 StepScope — 수동 실행에서 과거 날짜를 넘길 수 있어야 한다.
    @Bean
    @StepScope
    fun ledgerActiveUserReader(
        @Value("#{jobParameters['date']}") date: String?,
    ): JdbcCursorItemReader<Long> {
        val (from, to) = reconciliation.dayRange(date?.let(LocalDate::parse) ?: reconciliation.today())
        return JdbcCursorItemReaderBuilder<Long>()
            .name("ledgerActiveUserReader")
            .dataSource(dataSource)
            .sql(reconciliation.activeUsersSql())
            .preparedStatementSetter(ArgumentPreparedStatementSetter(arrayOf(Timestamp.from(from), Timestamp.from(to))))
            .rowMapper { rs, _ -> rs.getLong("user_id") }
            .build()
    }

    @Bean
    @StepScope
    fun ledgerReconciliationProcessor(
        @Value("#{jobParameters['date']}") date: String?,
    ): ItemProcessor<Long, ReconciliationResult> {
        val asOf = date?.let(LocalDate::parse) ?: reconciliation.today()
        return ItemProcessor { userId -> reconciliation.reconcile(userId, asOf) }
    }

    @Bean
    fun ledgerReconciliationWriter(): ItemWriter<ReconciliationResult> = ItemWriter { chunk ->
        val bad = chunk.items.count { it.mismatch }
        log.info("[LedgerReconciliation] chunk: {} users reconciled, {} mismatched", chunk.items.size, bad)
    }
}
