package com.monticker.api.batch.brokerage

import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.data.RepositoryItemReader
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate

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
            .reader(dueBrokerageSettlementReader())
            .processor(brokerageSettlementProcessor())
            .writer(brokerageSettlementWriter())
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(100)
            .build()

    @Bean
    fun dueBrokerageSettlementReader(): RepositoryItemReader<BrokerageSettlement> =
        RepositoryItemReaderBuilder<BrokerageSettlement>()
            .name("dueBrokerageSettlementReader")
            .repository(settlementRepo)
            .methodName("findDueSettlements")
            .arguments(listOf(LocalDate.now()))
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
