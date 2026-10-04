package com.monticker.api.common.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 결제 결과 카운터 (ADR-059).
 *
 * `payment_records` 는 테이블이고 알람은 메트릭을 본다 — 그 사이가 비어 있어서, 결제가 무더기로
 * 거절되는 상황을 알아챌 수단이 없었다. 서킷브레이커로는 잡히지 않는다: ADR-053 에서 4xx 를
 * 브레이커 집계에서 **일부러 뺐기** 때문이다(그래야 복구 경로의 정상적인 404 가 브레이커를
 * 열지 않는다). 즉 "PG 는 멀쩡한데 카드가 전부 거절된다"는 오직 이 카운터로만 보인다.
 *
 * 라벨 `kind` 는 PaymentFailureKind 와 같은 축이다 — 거절(고객 쪽)과 장애(PG 쪽)를 섞으면
 * 알람이 무엇을 말하는지 알 수 없다.
 */
@Component
class PaymentMetrics(private val registry: MeterRegistry) {

    init {
        // 카운터는 첫 증가 때 생긴다 — 미리 0으로 등록해 두지 않으면 결제가 한 번도 없는 동안
        // 시계열이 아예 없어서 비율 알람이 "데이터 없음"으로 조용히 통과한다.
        listOf(SUCCESS, DECLINED, UNAVAILABLE, INDETERMINATE)
            .forEach { registry.counter(METRIC, "kind", it) }
    }

    fun success() = count(SUCCESS)

    /** PG 가 정상 응답했고 거절했다. 사용자 조치가 필요한 쪽. */
    fun declined() = count(DECLINED)

    /** 요청이 PG 에 닿지 못했다. 고객 잘못이 아니다. */
    fun unavailable() = count(UNAVAILABLE)

    /** 응답을 못 받았다 — 청구 여부를 모른다. 이 값이 늘면 사람이 봐야 한다. */
    fun indeterminate() = count(INDETERMINATE)

    private fun count(kind: String) = registry.counter(METRIC, "kind", kind).increment()

    companion object {
        const val METRIC = "payment_result_total"
        const val SUCCESS = "success"
        const val DECLINED = "declined"
        const val UNAVAILABLE = "unavailable"
        const val INDETERMINATE = "indeterminate"
    }
}
