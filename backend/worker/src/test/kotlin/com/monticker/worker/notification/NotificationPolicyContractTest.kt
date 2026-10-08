package com.monticker.worker.notification

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * ADR-093 — `backend/contracts/notification-delivery-policy.json` 사례표를 worker 정책(실제 발송)으로 돌린다.
 * api `NotificationDeliveryPolicyContractTest`가 같은 표로 api 구현(전달 채널 표시)을 돌린다 — 두 구현이 어긋나면 한쪽이 실패한다.
 * 시각은 KST 벽시계로 주고 Instant로 바꿔 넘긴다: JVM 시간대(CI는 UTC)와 무관하게 같은 답이어야 한다.
 */
class NotificationPolicyContractTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)   // 표의 필드 이름이 worker 설정과 다르면 실패
    private val table: JsonNode = mapper.readTree(
        File(System.getProperty("monticker.contractsDir") ?: "../contracts", "notification-delivery-policy.json"),
    )

    @Test
    fun `worker categories match the shared table`() {
        val expected = table["categories"].associate { it["name"].asText() to it["alwaysOn"].asBoolean() }
        assertThat(NotificationCategory.entries.associate { it.name to it.alwaysOn }).isEqualTo(expected)
    }

    @Test
    fun `worker policy answers every case in the shared table`() {
        val soft = SoftAssertions()
        val cases = table["cases"]
        assertThat(cases.size()).isGreaterThan(10)
        for (c in cases) {
            val pref = mapper.treeToValue(c["pref"], NotificationPreference::class.java)
            val at = LocalDate.of(2026, 10, 8).atTime(LocalTime.parse(c["atKst"].asText())).atZone(ZoneId.of("Asia/Seoul")).toInstant()
            val plan = NotificationPolicy.plan(
                pref, NotificationCategory.valueOf(c["category"].asText()),
                marketingAgreed = c["marketingAgreed"]?.asBoolean() ?: false, at = at,
            )
            val e = c["expect"]
            soft.assertThat(plan).`as`(c["name"].asText()).isEqualTo(
                DeliveryPlan(e["push"].asBoolean(), e["email"].asBoolean(), e["emailIfPushMissed"].asBoolean(), e["quietHoursHeld"].asBoolean()),
            )
        }
        soft.assertAll()
    }
}
