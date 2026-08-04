package com.monticker.api.batch.subscription

import com.monticker.api.subscription.application.RenewResult
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.UserSubscription
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
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
import java.time.Instant
import java.time.temporal.ChronoUnit

@Configuration
class SubscriptionRenewalJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val subscriptionRepo: UserSubscriptionRepository,
    private val subscriptionService: SubscriptionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun subscriptionRenewalJob(): Job =
        JobBuilder("subscriptionRenewalJob", jobRepository)
            .start(subscriptionRenewalStep())
            .build()

    @Bean
    fun subscriptionRenewalStep(): Step =
        StepBuilder("subscriptionRenewalStep", jobRepository)
            .chunk<UserSubscription, UserSubscription>(20, transactionManager)
            .reader(expiringSubscriptionReader())
            .processor(renewalProcessor())
            .writer(renewalWriter())
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(50)
            .build()

    @Bean
    fun expiringSubscriptionReader(): RepositoryItemReader<UserSubscription> =
        RepositoryItemReaderBuilder<UserSubscription>()
            .name("expiringSubscriptionReader")
            .repository(subscriptionRepo)
            .methodName("findExpiringBefore")
            .arguments(listOf(Instant.now().plus(1, ChronoUnit.DAYS)))
            .sorts(mapOf("expiresAt" to Sort.Direction.ASC))
            .pageSize(20)
            .build()

    @Bean
    fun renewalProcessor(): ItemProcessor<UserSubscription, UserSubscription> =
        ItemProcessor { subscription ->
            val result = subscriptionService.renewSubscription(subscription)
            log.info("갱신 처리: userId={} result={}", subscription.userId, result::class.simpleName)
            when (result) {
                is RenewResult.Downgraded -> log.warn("FREE 다운그레이드: userId={}", subscription.userId)
                else -> {}
            }
            subscription
        }

    @Bean
    fun renewalWriter(): ItemWriter<UserSubscription> = ItemWriter { chunk ->
        log.info("구독 갱신 처리 완료: {}건", chunk.items.size)
    }
}
