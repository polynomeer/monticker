package com.monticker.api.common.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-059 — 알람이 보는 메트릭의 **계약**을 고정한다.
 *
 * `PaymentDeclineRateHigh` / `PaymentIndeterminateGrowing` 은 `payment_result_total` 의 이름과
 * `kind` 라벨 값에 문자열로 의존한다. 코드에서 라벨 하나를 바꾸면 알람은 에러도 내지 않고
 * 그냥 **영원히 조용해진다** — `OutboxBacklog`·`SagaIncomplete` 가 게이지에 `_total` 을 붙여
 * 빈 시계열을 보고 있었던 것과 같은 실패 모드다(resilience-plan §4.6).
 */
class PaymentMetricsTest {

    private fun registry() = SimpleMeterRegistry()

    @Test
    fun `결제가 한 번도 없어도 네 종류 모두 0으로 등록된다`() {
        // 미리 등록하지 않으면 시계열이 아예 없어서, 비율 알람이 "데이터 없음"으로 조용히
        // 통과한다. 결제가 드문 서비스에서는 그 상태가 기본값이 된다.
        val reg = registry()
        PaymentMetrics(reg)

        val kinds = reg.find(PaymentMetrics.METRIC).counters().map { it.id.getTag("kind") }
        assertThat(kinds).containsExactlyInAnyOrder("success", "declined", "unavailable", "indeterminate")
        assertThat(reg.find(PaymentMetrics.METRIC).counters().map { it.count() }).allMatch { it == 0.0 }
    }

    @Test
    fun `종류별로 따로 센다 — 거절과 PG 장애를 섞지 않는다`() {
        // 섞으면 알람이 "고객 카드 문제"와 "우리 PG 문제"를 구분하지 못한다. 대응이 정반대다.
        val reg = registry()
        val m = PaymentMetrics(reg)

        m.declined(); m.declined(); m.unavailable(); m.indeterminate(); m.success()

        fun count(kind: String) = reg.counter(PaymentMetrics.METRIC, "kind", kind).count()
        assertThat(count("declined")).isEqualTo(2.0)
        assertThat(count("unavailable")).isEqualTo(1.0)
        assertThat(count("indeterminate")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(1.0)
    }

    @Test
    fun `메트릭 이름은 알람 규칙과 같은 문자열이다`() {
        // alert-rules.yml 의 PromQL 이 이 이름을 직접 적는다. 바뀌면 알람이 조용해진다.
        assertThat(PaymentMetrics.METRIC).isEqualTo("payment_result_total")
    }
}
