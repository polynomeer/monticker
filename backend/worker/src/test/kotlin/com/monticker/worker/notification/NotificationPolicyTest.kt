package com.monticker.worker.notification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-082 — 설정·동의 → 발송 계획. */
class NotificationPolicyTest {
    private val defaults = NotificationPreference()

    @Test
    fun `끌 수 없는 종류는 전체 알림을 꺼도 푸시하고 닿지 않으면 이메일로 보낸다`() {
        val off = NotificationPreference(allEnabled = false, pushEnabled = false, emailEnabled = false)
        assertThat(NotificationPolicy.plan(off, NotificationCategory.ORDER_OUTCOME)).isEqualTo(DeliveryPlan.ALWAYS)
        assertThat(NotificationPolicy.plan(off, NotificationCategory.CONDITIONAL_ORDER)).isEqualTo(DeliveryPlan.ALWAYS)
    }

    @Test
    fun `분류가 없거나 모르는 와이어 값은 끌 수 없는 것으로 본다`() {
        assertThat(NotificationCategory.fromWire(null).alwaysOn).isTrue()
        assertThat(NotificationCategory.fromWire("SOMETHING_NEW").alwaysOn).isTrue()
        assertThat(NotificationCategory.fromWire("FILLS")).isEqualTo(NotificationCategory.FILLS)
    }

    @Test
    fun `기본값은 예전 동작 - 푸시, 닿지 않으면 이메일`() {
        assertThat(NotificationPolicy.plan(defaults, NotificationCategory.PRICE_ALERT))
            .isEqualTo(DeliveryPlan(push = true, email = false, emailIfPushMissed = true))
    }

    @Test
    fun `전체 알림을 끄면 끌 수 있는 종류는 아무것도 보내지 않는다`() {
        val plan = NotificationPolicy.plan(NotificationPreference(allEnabled = false), NotificationCategory.FILLS)
        assertThat(plan.none).isTrue()
    }

    @Test
    fun `종류를 끄면 보내지 않고, 이메일 채널을 끄면 이메일 대체도 없다`() {
        assertThat(NotificationPolicy.plan(NotificationPreference(volumeSurgePush = false), NotificationCategory.VOLUME_SURGE).none).isTrue()
        val noEmail = NotificationPolicy.plan(NotificationPreference(emailEnabled = false), NotificationCategory.QUANT_SIGNAL)
        assertThat(noEmail).isEqualTo(DeliveryPlan(push = true, email = false, emailIfPushMissed = false))
    }

    @Test
    fun `광고성 소식은 마케팅 동의와 설정이 모두 있어야 한다 - 기본은 꺼짐`() {
        val on = NotificationPreference(strategyMarketNewsPush = true)
        assertThat(NotificationPolicy.plan(defaults, NotificationCategory.STRATEGY_MARKET, marketingAgreed = true).none).isTrue()
        assertThat(NotificationPolicy.plan(on, NotificationCategory.STRATEGY_MARKET, marketingAgreed = false).none).isTrue()
        assertThat(NotificationPolicy.plan(on, NotificationCategory.STRATEGY_MARKET, marketingAgreed = true).push).isTrue()
    }

    // 보안 리뷰 — 푸시 채널을 끈 사용자에게 "푸시가 안 닿았다"며 이메일을 보내면 안 된다(끈 것은 푸시이지 실패가 아니다).
    @Test
    fun `푸시 채널을 끈 사용자에게는 푸시 대체 이메일을 보내지 않는다`() {
        val pushOff = NotificationPreference(pushEnabled = false)
        assertThat(NotificationPolicy.plan(pushOff, NotificationCategory.PRICE_ALERT).none).isTrue()
        assertThat(NotificationPolicy.plan(pushOff, NotificationCategory.FILLS).none).isTrue()
    }

    @Test
    fun `푸시를 끄고 이메일을 고른 종류는 이메일만 보낸다`() {
        val pref = NotificationPreference(pushEnabled = false, priceAlertEmail = true)
        assertThat(NotificationPolicy.plan(pref, NotificationCategory.PRICE_ALERT))
            .isEqualTo(DeliveryPlan(push = false, email = true, emailIfPushMissed = false))
    }

    // 광고성 정보는 사용자가 고른 채널로만 보낸다 — 푸시 실패를 이유로 이메일로 옮기지 않는다(ADR-068).
    @Test
    fun `광고성 소식은 푸시가 닿지 않아도 이메일로 대신 보내지 않는다`() {
        val on = NotificationPreference(strategyMarketNewsPush = true)
        assertThat(NotificationPolicy.plan(on, NotificationCategory.STRATEGY_MARKET, marketingAgreed = true))
            .isEqualTo(DeliveryPlan(push = true, email = false, emailIfPushMissed = false))
    }

    // ── ADR-093 방해 금지 시간 ─────────────────────────────────────────────

    private val night = NotificationPreference(quietHoursEnabled = true)   // 22:00~07:00
    private fun kst(date: String, time: String) =
        java.time.LocalDateTime.parse("${date}T$time").atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()

    @Test
    fun `자정을 넘는 구간은 시작 포함 끝 제외로 하루 1440분 중 정확히 540분이다`() {
        val quietMinutes = (0 until 1440).count { night.inQuietHours(java.time.LocalTime.MIN.plusMinutes(it.toLong())) }
        assertThat(quietMinutes).isEqualTo(9 * 60)
        assertThat(night.inQuietHours(java.time.LocalTime.of(21, 59))).isFalse()
        assertThat(night.inQuietHours(java.time.LocalTime.of(22, 0))).isTrue()
        assertThat(night.inQuietHours(java.time.LocalTime.MIDNIGHT)).isTrue()
        assertThat(night.inQuietHours(java.time.LocalTime.of(6, 59))).isTrue()
        assertThat(night.inQuietHours(java.time.LocalTime.of(7, 0))).isFalse()
    }

    @Test
    fun `시작과 끝이 같으면 구간이 없다 - 모호한 값으로 하루 종일 막지 않는다`() {
        val same = NotificationPreference(quietHoursEnabled = true, quietHoursStart = java.time.LocalTime.NOON, quietHoursEnd = java.time.LocalTime.NOON)
        assertThat((0 until 1440).none { same.inQuietHours(java.time.LocalTime.MIN.plusMinutes(it.toLong())) }).isTrue()
    }

    // KST는 서머타임이 없다. JVM 기본 시간대가 서머타임 지역이고 그 전환일(미국 2026-11-01)이어도 판정은 KST 벽시계로 한다.
    @Test
    fun `판정은 JVM 시간대가 아니라 KST 벽시계로 한다 - 서머타임 전환일에도`() {
        val original = java.util.TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/New_York", "Europe/London")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                for (date in listOf("2026-03-08", "2026-11-01", "2026-03-29", "2026-10-25")) {
                    assertThat(NotificationPolicy.plan(night, NotificationCategory.PRICE_ALERT, at = kst(date, "23:30")).quietHoursHeld)
                        .`as`("$zone $date 23:30 KST").isTrue()
                    assertThat(NotificationPolicy.plan(night, NotificationCategory.PRICE_ALERT, at = kst(date, "07:00")).push)
                        .`as`("$zone $date 07:00 KST").isTrue()
                }
            }
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun `끌 수 없는 종류는 방해 금지 시간에도 그대로 즉시 보낸다`() {
        for (category in NotificationCategory.entries.filter { it.alwaysOn }) {
            assertThat(NotificationPolicy.plan(night, category, at = kst("2026-10-08", "02:00"))).`as`(category.name).isEqualTo(DeliveryPlan.ALWAYS)
        }
    }

    @Test
    fun `방해 금지 시간에 끌 수 있는 종류는 푸시를 보류하고 그 사실을 표시한다`() {
        val plan = NotificationPolicy.plan(night, NotificationCategory.FILLS, at = kst("2026-10-08", "02:00"))
        assertThat(plan).isEqualTo(DeliveryPlan(push = false, email = false, emailIfPushMissed = false, quietHoursHeld = true))
        assertThat(plan.none).isTrue()
    }
}
