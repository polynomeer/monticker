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
}
