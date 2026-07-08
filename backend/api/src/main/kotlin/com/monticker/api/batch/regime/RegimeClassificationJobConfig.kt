package com.monticker.api.batch.regime

import com.monticker.api.analytics.application.RegimeDetectorService
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
import javax.sql.DataSource

@Configuration
class RegimeClassificationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val regimeDetectorService: RegimeDetectorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun regimeClassificationJob(): Job =
        JobBuilder("regimeClassificationJob", jobRepository)
            .start(regimeClassificationStep())
            .build()

    @Bean
    fun regimeClassificationStep(): Step =
        StepBuilder("regimeClassificationStep", jobRepository)
            .chunk<Long, Long>(10, transactionManager)
            .reader(activeStockReader())
            .processor(regimeItemProcessor())
            .writer(regimeNoopWriter())
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(20)          // 20개 종목까지 skip, 나머지 계속 처리
            .build()

    @Bean
    fun activeStockReader(): JdbcCursorItemReader<Long> =
        JdbcCursorItemReaderBuilder<Long>()
            .name("activeStockReader")
            .dataSource(dataSource)
            .sql("SELECT id FROM stocks WHERE is_active = true ORDER BY id")
            .rowMapper { rs, _ -> rs.getLong("id") }
            .build()

    @Bean
    fun regimeItemProcessor(): ItemProcessor<Long, Long> = ItemProcessor { stockId ->
        regimeDetectorService.classifyRegime(stockId)
        stockId
    }

    // 저장은 RegimeDetectorService.classifyRegime() 내부에서 처리
    @Bean
    fun regimeNoopWriter(): ItemWriter<Long> = ItemWriter { chunk ->
        log.debug("Regime classified for {} stocks", chunk.items.size)
    }
}
