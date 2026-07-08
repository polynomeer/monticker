package com.monticker.api.batch.score

import com.monticker.api.wallet.application.BehaviorScoreService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import javax.sql.DataSource

@Configuration
class BehaviorScoreJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val behaviorScoreService: BehaviorScoreService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun behaviorScoreJob(): Job =
        JobBuilder("behaviorScoreJob", jobRepository)
            .start(behaviorScoreStep())
            .build()

    @Bean
    fun behaviorScoreStep(): Step =
        StepBuilder("behaviorScoreStep", jobRepository)
            .chunk<Long, Long>(20, transactionManager)
            .reader(activeUserReader())
            .processor(behaviorScoreProcessor())
            .writer(behaviorScoreNoopWriter())
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(50)
            .build()

    // paper_trade가 1건 이상 있는 사용자만 대상
    @Bean
    fun activeUserReader(): JdbcCursorItemReader<Long> =
        JdbcCursorItemReaderBuilder<Long>()
            .name("activeUserReader")
            .dataSource(dataSource)
            .sql("""
                SELECT DISTINCT user_id
                FROM paper_trades
                ORDER BY user_id
            """.trimIndent())
            .rowMapper { rs, _ -> rs.getLong("user_id") }
            .build()

    @Bean
    fun behaviorScoreProcessor(): ItemProcessor<Long, Long> = ItemProcessor { userId ->
        behaviorScoreService.recalculate(userId, LocalDate.now())
        userId
    }

    @Bean
    fun behaviorScoreNoopWriter(): ItemWriter<Long> = ItemWriter { chunk ->
        log.debug("BehaviorScore recalculated for {} users", chunk.items.size)
    }
}
