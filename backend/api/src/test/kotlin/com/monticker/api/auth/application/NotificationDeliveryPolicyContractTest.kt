package com.monticker.api.auth.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.auth.api.NotificationPreferenceRequest
import com.monticker.api.common.notification.NotificationCategory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.reflect.full.memberProperties

/**
 * ADR-093 — `backend/contracts/notification-delivery-policy.json` 사례표를 api 거울([NotificationDeliveryPolicy])로 돌린다.
 * worker `NotificationPolicyContractTest`가 같은 표로 실제 발송 정책을 돌린다 — 두 구현이 어긋나면 한쪽이 실패한다.
 */
class NotificationDeliveryPolicyContractTest {
    private val mapper = jacksonObjectMapper()
    private val table: JsonNode = mapper.readTree(
        File(System.getProperty("monticker.contractsDir") ?: "../contracts", "notification-delivery-policy.json"),
    )

    @Test
    fun `api categories match the shared table - name, alwaysOn, inApp`() {
        val expected = table["categories"].associate { it["name"].asText() to (it["alwaysOn"].asBoolean() to it["inApp"].asBoolean()) }
        assertThat(NotificationCategory.entries.associate { it.name to (it.alwaysOn to it.inApp) }).isEqualTo(expected)
    }

    // DTO는 모르는 필드를 무시한다(옛 Redis 값 호환) — 표의 필드 이름이 api 설정 필드와 같은지는 따로 확인한다.
    @Test
    fun `every preference field the table uses exists on the api request`() {
        val fields = NotificationPreferenceRequest::class.memberProperties.map { it.name }.toSet()
        val used = table["cases"].flatMap { it["pref"].fieldNames().asSequence().toList() }.toSet()
        assertThat(fields).containsAll(used)
    }

    @Test
    fun `api policy answers every case in the shared table`() {
        val soft = SoftAssertions()
        val cases = table["cases"]
        assertThat(cases.size()).isGreaterThan(10)
        for (c in cases) {
            val pref = mapper.treeToValue(c["pref"], NotificationPreferenceRequest::class.java)
            val at = LocalDate.of(2026, 10, 8).atTime(LocalTime.parse(c["atKst"].asText())).atZone(ZoneId.of("Asia/Seoul")).toInstant()
            val plan = NotificationDeliveryPolicy.plan(
                pref, NotificationCategory.valueOf(c["category"].asText()), c["marketingAgreed"]?.asBoolean() ?: false, at,
            )
            val e = c["expect"]
            soft.assertThat(plan).`as`(c["name"].asText()).isEqualTo(
                NotificationDeliveryPolicy.Plan(e["push"].asBoolean(), e["email"].asBoolean(), e["emailIfPushMissed"].asBoolean(), e["quietHoursHeld"].asBoolean()),
            )
        }
        soft.assertAll()
    }
}
