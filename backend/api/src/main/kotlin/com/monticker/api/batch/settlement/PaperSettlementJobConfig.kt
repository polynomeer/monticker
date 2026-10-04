package com.monticker.api.batch.settlement

import com.monticker.api.paper.application.PaperSettlementService
import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.data.RepositoryItemReader
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import java.time.ZoneId

@Configuration
class PaperSettlementJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val settlementRepo: PaperSettlementRepository,
    private val settlementService: PaperSettlementService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun paperSettlementJob(): Job =
        JobBuilder("paperSettlementJob", jobRepository)
            .start(paperSettlementStep())
            .build()

    @Bean
    fun paperSettlementStep(): Step =
        StepBuilder("paperSettlementStep", jobRepository)
            .chunk<PaperSettlement, PaperSettlement>(50, transactionManager)
            .reader(dueSettlementReader(null))
            .processor(settlementProcessor())
            .writer(settlementWriter())
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(100)
            .build()

    // StepScope — 싱글턴 빈에서 LocalDate.now()를 인자로 굳히면 기동한 날짜로 고정돼, JVM이 떠 있는 동안
    // 그 뒤에 기준일이 도래한 정산을 영영 읽지 않는다(2026-10 설계 리뷰). 실행마다 잡 파라미터의 date를 쓴다.
    @Bean
    @StepScope
    fun dueSettlementReader(
        @Value("#{jobParameters['date']}") date: String?,
    ): RepositoryItemReader<PaperSettlement> =
        RepositoryItemReaderBuilder<PaperSettlement>()
            .name("dueSettlementReader")
            .repository(settlementRepo)
            .methodName("findDueSettlements")
            .arguments(listOf(date?.let(LocalDate::parse) ?: LocalDate.now(ZoneId.of("Asia/Seoul"))))
            .sorts(mapOf("settleDate" to Sort.Direction.ASC))
            .pageSize(50)
            .build()

    @Bean
    fun settlementProcessor(): ItemProcessor<PaperSettlement, PaperSettlement> =
        ItemProcessor { settlement ->
            try {
                settlementService.settle(settlement)
                settlement
            } catch (e: Exception) {
                log.error("정산 처리 실패: id={}, error={}", settlement.id, e.message)
                throw e
            }
        }

    @Bean
    fun settlementWriter(): ItemWriter<PaperSettlement> = ItemWriter { chunk ->
        log.info("정산 완료: {}건", chunk.items.size)
    }
}
