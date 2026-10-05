package com.monticker.api.batch.payment

import com.monticker.api.batch.KeysetItemReader
import com.monticker.api.subscription.application.PaymentResolution
import com.monticker.api.subscription.application.SubscriptionService
import com.monticker.api.subscription.domain.PaymentRecord
import com.monticker.api.subscription.domain.PaymentStatus
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant

/**
 * ADR-059 — PENDING 결제 적체 청소.
 *
 * [com.monticker.api.subscription.application.SubscriptionService] 는 PG 장애·불확정 상태에서
 * 결제를 **FAILED 로 굳히지 않고 PENDING 으로 남긴다**(ADR-053). 그게 이중청구와 오강등을 막는
 * 유일한 방법이지만, 그 상태를 정리하는 주체가 없으면 적체가 영원히 늘고 두 가지가 썩는다:
 *
 *  1. **실제로는 청구된 결제가 PENDING 으로 방치된다** — 고객은 돈을 냈는데 구독이 없다.
 *  2. 연속 실패 카운트의 기준이 흐려져 강등 판정이 어긋난다.
 *
 * 갱신 배치는 월 1회라 그 역할을 대신할 수 없다. 이 잡이 자주 돌면서 PG 에 되물어 정리한다 —
 * 판단 로직은 `SubscriptionService.resolvePendingPayment()` 하나를 공유한다.
 *
 * 재실행이 안전하다: 모든 판단이 PG 재조회 결과에서 나오고, 확정된 건은 다음 실행에서 더 이상
 * PENDING 이 아니라 읽히지 않는다.
 */
@Configuration
class PaymentReconciliationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val paymentRepo: PaymentRecordRepository,
    private val subscriptionService: SubscriptionService,
    /** 이보다 최근에 만들어진 PENDING 은 건드리지 않는다 — 아직 확정 중일 수 있다. */
    @Value("\${app.payment.reconcile.stale-after-minutes:10}") private val staleAfterMinutes: Long,
    /** PG 가 "그런 결제 없다"고 답하는 상태로 이만큼 지나면 실패로 닫는다. */
    @Value("\${app.payment.reconcile.abandon-after-hours:72}") private val abandonAfterHours: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun paymentReconciliationJob(): Job =
        JobBuilder("paymentReconciliationJob", jobRepository)
            .start(paymentReconciliationStep())
            .build()

    @Bean
    fun paymentReconciliationStep(): Step =
        StepBuilder("paymentReconciliationStep", jobRepository)
            .chunk<PaymentRecord, PaymentRecord>(20, transactionManager)
            .reader(stalePendingPaymentReader())
            .processor(paymentReconciliationProcessor())
            .writer(paymentReconciliationWriter())
            .faultTolerant()
            // 한 건의 PG 조회 실패가 나머지 정리를 막아서는 안 된다. 삼키는 게 아니라
            // 다음 실행이 다시 집어간다 — PENDING 이 그대로 남아 있기 때문이다.
            .skip(Exception::class.java)
            .skipLimit(50)
            .build()

    /**
     * `@StepScope` 가 필요하다 — 싱글턴이면 `Instant.now()` 가 **기동 시각**으로 굳어, 며칠 떠
     * 있는 인스턴스는 영원히 그 시점 기준으로만 PENDING 을 찾는다. 갱신 배치의 리더가 정확히
     * 그 상태였고 CH-13 에서 드러났다(ADR-053).
     *
     * 키셋으로 읽는다 — 정리된 건은 PENDING 에서 빠지므로 offset 페이징은 20건씩 건너뛴다. 예전
     * `RepositoryItemReader` 는 리포지토리가 `List` 를 돌려줘 `Slice` 캐스팅에서 매번 죽었다(첫 read 부터
     * ClassCastException → skip limit 초과 → FAILED). 이 잡은 한 번도 PENDING 을 정리한 적이 없었다.
     */
    @Bean
    @StepScope
    fun stalePendingPaymentReader(): KeysetItemReader<PaymentRecord> {
        val before = Instant.now().minus(Duration.ofMinutes(staleAfterMinutes))
        return KeysetItemReader("stalePendingPaymentReader", 20, PaymentRecord::id) { afterId, limit ->
            paymentRepo.findAllByStatusAndPgOrderIdIsNotNullAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(
                PaymentStatus.PENDING, before, afterId, PageRequest.of(0, limit),
            )
        }
    }

    @Bean
    fun paymentReconciliationProcessor(): ItemProcessor<PaymentRecord, PaymentRecord> =
        ItemProcessor { record ->
            when (subscriptionService.resolvePendingPayment(record)) {
                PaymentResolution.PAID ->
                    log.warn("PENDING 결제를 성공으로 정리: id={} orderId={} — 고객은 이미 돈을 냈다",
                        record.id, record.pgOrderId)

                PaymentResolution.FAILED ->
                    log.info("PENDING 결제를 실패로 정리: id={} orderId={}", record.id, record.pgOrderId)

                // PG 장애. 아무것도 단정하지 않고 다음 실행에 맡긴다.
                PaymentResolution.DEFERRED ->
                    log.warn("PG 조회 실패 — 보류 유지: id={} orderId={}", record.id, record.pgOrderId)

                // PG 에 그 결제가 없다 = 청구된 적 없다. 갱신 배치가 다시 긁을 수 있으므로
                // 바로 닫지 않고, 너무 오래된 것만 닫는다.
                PaymentResolution.NOT_CHARGED -> {
                    val abandonBefore = Instant.now().minus(Duration.ofHours(abandonAfterHours))
                    if (record.createdAt.isBefore(abandonBefore)) {
                        subscriptionService.expirePendingPayment(
                            record, "${abandonAfterHours}시간 동안 PG에 결제 기록이 없었습니다.",
                        )
                    }
                }
            }
            record
        }

    @Bean
    fun paymentReconciliationWriter(): ItemWriter<PaymentRecord> = ItemWriter { chunk ->
        log.info("PENDING 결제 정리: {}건 처리", chunk.items.size)
    }
}
