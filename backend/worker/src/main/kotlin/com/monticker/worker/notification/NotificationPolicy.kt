package com.monticker.worker.notification

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * ADR-082 — 알림 종류. api `NotificationCategory`(notify.user 와이어)의 상위집합이다: PRICE_ALERT·VOLUME_SURGE는 worker 자체 경로
 * (알림 규칙 `AlertDispatcher`, 관심종목 이벤트 `StockEventWriter`)가 쓴다.
 */
enum class NotificationCategory(val alwaysOn: Boolean = false) {
    ORDER_OUTCOME(alwaysOn = true),
    CONDITIONAL_ORDER(alwaysOn = true),
    RISK_WARNING(alwaysOn = true),
    FILLS,
    QUANT_SIGNAL,
    STRATEGY_MARKET,
    PRICE_ALERT,
    VOLUME_SURGE,
    ;

    companion object {
        /**
         * 와이어 값 → 종류. 없으면(이 변경 전 api가 보낸 메시지 — 당시 알림은 전부 끌 수 없는 종류였다) 끌 수 없는 것으로 본다.
         * 모르는 이름(api가 더 새 버전)도 마찬가지로 보낸다 — 새 종류가 생겼다는 이유로 주문 결과 알림을 버리지 않는다. 광고성 종류를
         * 새로 만들 때는 worker를 먼저 배포한다(ADR-082 Consequences).
         */
        fun fromWire(name: String?): NotificationCategory =
            entries.firstOrNull { it.name == name } ?: ORDER_OUTCOME
    }
}

/** 사용자 알림 설정(api `NotificationPreferenceRequest`·V78/V90과 같은 필드·기본값). */
data class NotificationPreference(
    val allEnabled: Boolean = true,
    val pushEnabled: Boolean = true,
    val emailEnabled: Boolean = true,
    val priceAlertPush: Boolean = true,
    val priceAlertEmail: Boolean = false,
    val volumeSurgePush: Boolean = true,
    val volumeSurgeEmail: Boolean = false,
    val newsAlertPush: Boolean = true,
    val newsAlertEmail: Boolean = false,
    val quantSignalPush: Boolean = true,
    val quantSignalEmail: Boolean = false,
    val fillsPush: Boolean = true,
    val fillsEmail: Boolean = false,
    val strategyMarketNewsPush: Boolean = false,
    val strategyMarketNewsEmail: Boolean = false,
    val weeklyReportEmail: Boolean = true,
    /** ADR-093 — 방해 금지 시간(KST 벽시계 시각, 자정을 넘을 수 있다). 기본 꺼짐. */
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: LocalTime = LocalTime.of(22, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(7, 0),
) {
    /**
     * [kstTime]이 방해 금지 구간인가. 시작 포함·끝 제외. 시작 > 끝이면 자정을 넘는 구간(22:00~07:00 = 22:00~24:00 ∪ 00:00~07:00).
     * 시작 = 끝은 저장되지 않지만(V90 CHECK) 들어오면 구간 없음으로 본다 — 모호한 값으로 알림을 하루 종일 막지 않는다.
     */
    fun inQuietHours(kstTime: LocalTime): Boolean {
        if (!quietHoursEnabled || quietHoursStart == quietHoursEnd) return false
        return if (quietHoursStart < quietHoursEnd) kstTime >= quietHoursStart && kstTime < quietHoursEnd
        else kstTime >= quietHoursStart || kstTime < quietHoursEnd
    }

    /** 종류별 (푸시, 이메일) 선택. 끌 수 없는 종류는 여기서 묻지 않는다. */
    fun channels(category: NotificationCategory): Pair<Boolean, Boolean> = when (category) {
        NotificationCategory.PRICE_ALERT -> priceAlertPush to priceAlertEmail
        NotificationCategory.VOLUME_SURGE -> volumeSurgePush to volumeSurgeEmail
        NotificationCategory.QUANT_SIGNAL -> quantSignalPush to quantSignalEmail
        NotificationCategory.FILLS -> fillsPush to fillsEmail
        NotificationCategory.STRATEGY_MARKET -> strategyMarketNewsPush to strategyMarketNewsEmail
        NotificationCategory.ORDER_OUTCOME, NotificationCategory.CONDITIONAL_ORDER, NotificationCategory.RISK_WARNING -> true to false
    }
}

/**
 * 한 알림을 어떻게 보낼지.
 * @property push 등록 기기로 푸시한다
 * @property email 푸시와 별개로 이메일도 보낸다(사용자가 이 종류의 이메일을 골랐다)
 * @property emailIfPushMissed 푸시를 원했는데 어느 기기에도 닿지 않았으면(기기 없음·서킷 OPEN) 이메일로 대신 보낸다 — 예전 동작
 * @property quietHoursHeld 푸시를 보냈을 텐데 방해 금지 시간이라 보내지 않았다(ADR-093) — 이력 상태를 `QUIET_HOURS`로 남기는 데 쓴다
 */
data class DeliveryPlan(
    val push: Boolean,
    val email: Boolean,
    val emailIfPushMissed: Boolean,
    val quietHoursHeld: Boolean = false,
) {
    val none: Boolean get() = !push && !email && !emailIfPushMissed

    companion object {
        val NONE = DeliveryPlan(push = false, email = false, emailIfPushMissed = false)
        /** 끌 수 없는 알림 — 푸시, 닿지 않으면 이메일(ADR-065). */
        val ALWAYS = DeliveryPlan(push = true, email = false, emailIfPushMissed = true)
    }
}

/**
 * ADR-082/093 — 설정·동의·시각을 발송 계획으로. 순수 함수라 발송 경로가 모두 같은 규칙을 쓴다.
 * api `NotificationDeliveryPolicy`(전달 채널 표시)가 같은 규칙을 따로 구현한다 — 둘 다 `backend/contracts/notification-delivery-policy.json`
 * 사례표로 테스트한다. 규칙을 바꾸면 그 표부터 고친다.
 */
object NotificationPolicy {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** [at] 시각 기준. 방해 금지 시간 판정은 KST 벽시계 시각으로 한다(JVM 기본 시간대와 무관). */
    fun plan(
        pref: NotificationPreference,
        category: NotificationCategory,
        marketingAgreed: Boolean = false,
        at: Instant = Instant.now(),
    ): DeliveryPlan = plan(pref, category, marketingAgreed, inQuietHours = pref.inQuietHours(at.atZone(KST).toLocalTime()))

    fun plan(
        pref: NotificationPreference,
        category: NotificationCategory,
        marketingAgreed: Boolean,
        inQuietHours: Boolean,
    ): DeliveryPlan {
        // 끌 수 없는 알림은 설정도 방해 금지 시간도 보지 않는다 — 결과 불명 주문·보호 상실·리스크 경고는 밤에도 바로 알아야 한다.
        if (category.alwaysOn) return DeliveryPlan.ALWAYS
        if (!pref.allEnabled) return DeliveryPlan.NONE
        // 광고성 정보는 수신 동의가 없으면 설정과 무관하게 보내지 않는다(정보통신망법 §50, ADR-068).
        if (category == NotificationCategory.STRATEGY_MARKET && !marketingAgreed) return DeliveryPlan.NONE
        val (wantPush, wantEmail) = pref.channels(category)
        val push = pref.pushEnabled && wantPush
        val email = pref.emailEnabled && wantEmail
        // ADR-093 — 방해 금지 시간에는 푸시를 보내지 않는다(미루지 않는다). 푸시 대체 이메일도 없다: 대체는 "닿지 않은" 푸시를 위한
        // 것이지 일부러 보내지 않은 푸시를 위한 것이 아니다. 사용자가 고른 이메일은 소리가 나지 않으니 그대로 보낸다.
        if (inQuietHours && push) return DeliveryPlan(push = false, email = email, emailIfPushMissed = false, quietHoursHeld = true)
        return DeliveryPlan(
            push = push,
            email = email,
            // 대체 이메일은 "푸시를 보내려 했는데 닿지 않은" 경우만이다. 푸시 채널을 끈 사용자에게는 보낼 푸시가 없으므로 대체도 없다.
            // 광고성 정보는 사용자가 고른 채널로만 보낸다 — 푸시 실패를 이유로 이메일로 옮기지 않는다(ADR-068).
            emailIfPushMissed = push && pref.emailEnabled && !wantEmail && category != NotificationCategory.STRATEGY_MARKET,
        )
    }
}
