package com.monticker.api.auth.application

import com.monticker.api.auth.api.NotificationPreferenceRequest
import com.monticker.api.common.notification.NotificationCategory
import java.time.Instant
import java.time.ZoneId

/**
 * ADR-093 — 알림 한 건을 어느 채널로 보낼지. **실제 발송은 worker `NotificationPolicy`가 정한다.** 이것은 그 규칙을 api에서
 * 다시 계산해 화면(전달 채널)에 보여 주기 위한 거울이다.
 *
 * api와 worker는 빌드·이미지가 따로라 코드를 공유할 수 없다. 대신 규칙을 `backend/contracts/notification-delivery-policy.json`
 * 사례표 한 곳에 두고, 이 객체와 worker 정책이 둘 다 그 표로 테스트된다(`NotificationDeliveryPolicyContractTest`). 규칙을
 * 바꾸면 표부터 고치고 양쪽을 맞춘다 — 한쪽만 바꾸면 다른 쪽 테스트가 실패한다.
 */
object NotificationDeliveryPolicy {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** worker `DeliveryPlan`과 같은 뜻 */
    data class Plan(val push: Boolean, val email: Boolean, val emailIfPushMissed: Boolean, val quietHoursHeld: Boolean = false) {
        companion object {
            val NONE = Plan(push = false, email = false, emailIfPushMissed = false)
            val ALWAYS = Plan(push = true, email = false, emailIfPushMissed = true)
        }
    }

    fun inQuietHours(pref: NotificationPreferenceRequest, at: Instant): Boolean =
        pref.quietHours.contains(at.atZone(KST).toLocalTime())

    fun plan(pref: NotificationPreferenceRequest, category: NotificationCategory, marketingAgreed: Boolean, at: Instant): Plan =
        plan(pref, category, marketingAgreed, inQuietHours = inQuietHours(pref, at))

    fun plan(pref: NotificationPreferenceRequest, category: NotificationCategory, marketingAgreed: Boolean, inQuietHours: Boolean): Plan {
        if (category.alwaysOn) return Plan.ALWAYS
        if (!pref.allEnabled) return Plan.NONE
        if (category == NotificationCategory.STRATEGY_MARKET && !marketingAgreed) return Plan.NONE
        val (wantPush, wantEmail) = channels(pref, category)
        val push = pref.pushEnabled && wantPush
        val email = pref.emailEnabled && wantEmail
        if (inQuietHours && push) return Plan(push = false, email = email, emailIfPushMissed = false, quietHoursHeld = true)
        return Plan(
            push = push,
            email = email,
            emailIfPushMissed = push && pref.emailEnabled && !wantEmail && category != NotificationCategory.STRATEGY_MARKET,
        )
    }

    private fun channels(p: NotificationPreferenceRequest, category: NotificationCategory): Pair<Boolean, Boolean> = when (category) {
        NotificationCategory.PRICE_ALERT -> p.priceAlertPush to p.priceAlertEmail
        NotificationCategory.VOLUME_SURGE -> p.volumeSurgePush to p.volumeSurgeEmail
        NotificationCategory.QUANT_SIGNAL -> p.quantSignalPush to p.quantSignalEmail
        NotificationCategory.FILLS -> p.fillsPush to p.fillsEmail
        NotificationCategory.STRATEGY_MARKET -> p.strategyMarketNewsPush to p.strategyMarketNewsEmail
        NotificationCategory.ORDER_OUTCOME, NotificationCategory.CONDITIONAL_ORDER, NotificationCategory.RISK_WARNING -> true to false
    }
}
