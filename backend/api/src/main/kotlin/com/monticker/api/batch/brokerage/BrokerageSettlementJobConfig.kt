package com.monticker.api.batch.brokerage

import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
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
class BrokerageSettlementJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val settlementRepo: BrokerageSettlementRepository,
    private val brokerageService: BrokerageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun brokerageSettlementJob(): Job =
        JobBuilder("brokerageSettlementJob", jobRepository)
            .start(brokerageSettlementStep())
            .build()

    @Bean
    fun brokerageSettlementStep(): Step =
        StepBuilder("brokerageSettlementStep", jobRepository)
            .chunk<BrokerageSettlement, BrokerageSettlement>(50, transactionManager)
            .reader(dueBrokerageSettlementReader(null))
            .processor(brokerageSettlementProcessor())
            .writer(brokerageSettlementWriter())
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(100)
            .build()

    // StepScope — 싱글턴 빈에서 LocalDate.now()를 인자로 굳히면 기동한 날짜로 고정돼, JVM이 떠 있는 동안
    // 그 뒤에 기준일이 도래한 정산을 영영 읽지 않는다(2026-10 설계 리뷰). 실행마다 잡 파라미터의 date를 쓴다.
    @Bean
    @StepScope
    fun dueBrokerageSettlementReader(
        @Value("#{jobParameters['date']}") date: String?,
    ): RepositoryItemReader<BrokerageSettlement> =
        RepositoryItemReaderBuilder<BrokerageSettlement>()
            .name("dueBrokerageSettlementReader")
            .repository(settlementRepo)
            .methodName("findDueSettlements")
            .arguments(listOf(date?.let(LocalDate::parse) ?: LocalDate.now(ZoneId.of("Asia/Seoul"))))
            .sorts(mapOf("settleDate" to Sort.Direction.ASC))
            .pageSize(50)
            .build()

    @Bean
    fun brokerageSettlementProcessor(): ItemProcessor<BrokerageSettlement, BrokerageSettlement> =
        ItemProcessor { settlement ->
            try {
                brokerageService.settle(settlement)
                settlement
            } catch (e: Exception) {
                log.error("증권사 정산 처리 실패: id={} error={}", settlement.id, e.message)
                throw e
            }
        }

    @Bean
    fun brokerageSettlementWriter(): ItemWriter<BrokerageSettlement> = ItemWriter { chunk ->
        log.info("증권사 정산 완료: {}건", chunk.items.size)
    }
}
