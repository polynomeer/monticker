package com.monticker.api.batch.subscription

import com.monticker.api.batch.KeysetItemReader
import com.monticker.api.subscription.application.RenewResult
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.UserSubscription
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
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

    /**
     * @StepScope가 꼭 필요하다 — 싱글턴 빈이면 `Instant.now()`가 **애플리케이션 기동 시각**으로
     * 한 번 굳어, 몇 주째 떠 있는 인스턴스는 영원히 기동일 기준으로 만료 예정을 찾는다.
     * 스텝 스코프면 실행할 때마다 다시 계산된다.
     */
    @Bean
    @StepScope
    fun expiringSubscriptionReader(): KeysetItemReader<UserSubscription> {
        // 키셋 — 갱신(expiresAt 연장)·강등(status 변경)되면 조건에서 빠지므로 offset 페이징은 20건씩 건너뛴다.
        val threshold = Instant.now().plus(1, ChronoUnit.DAYS)
        return KeysetItemReader("expiringSubscriptionReader", 20, UserSubscription::id) { afterId, limit ->
            subscriptionRepo.findExpiringBeforeAfter(threshold, afterId, PageRequest.of(0, limit))
        }
    }

    @Bean
    fun renewalProcessor(): ItemProcessor<UserSubscription, UserSubscription> =
        ItemProcessor { subscription ->
            val result = subscriptionService.renewSubscription(subscription)
            log.info("갱신 처리: userId={} result={}", subscription.userId, result::class.simpleName)
            when (result) {
                is RenewResult.Downgraded -> log.warn("FREE 다운그레이드: userId={}", subscription.userId)
                // ADR-053 — PG 장애·불확정. 다음 배치가 같은 orderId로 이어서 시도한다.
                // 이 줄이 계속 쌓이면 PG를 의심해야지 고객 카드를 의심할 일이 아니다.
                is RenewResult.Deferred -> log.warn("갱신 보류 (PG 장애/불확정): userId={}", subscription.userId)
                else -> {}
            }
            subscription
        }

    @Bean
    fun renewalWriter(): ItemWriter<UserSubscription> = ItemWriter { chunk ->
        log.info("구독 갱신 처리 완료: {}건", chunk.items.size)
    }
}
