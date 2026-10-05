package com.monticker.worker.notification

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

/** 사용자 알림 설정(api `NotificationPreferenceRequest`·V78과 같은 필드·기본값). */
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
) {
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
 */
data class DeliveryPlan(val push: Boolean, val email: Boolean, val emailIfPushMissed: Boolean) {
    val none: Boolean get() = !push && !email && !emailIfPushMissed

    companion object {
        val NONE = DeliveryPlan(push = false, email = false, emailIfPushMissed = false)
        /** 끌 수 없는 알림 — 푸시, 닿지 않으면 이메일(ADR-065). */
        val ALWAYS = DeliveryPlan(push = true, email = false, emailIfPushMissed = true)
    }
}

/** ADR-082 — 설정·동의를 발송 계획으로. 순수 함수라 세 발송 경로가 같은 규칙을 쓴다. */
object NotificationPolicy {
    fun plan(pref: NotificationPreference, category: NotificationCategory, marketingAgreed: Boolean = false): DeliveryPlan {
        if (category.alwaysOn) return DeliveryPlan.ALWAYS
        if (!pref.allEnabled) return DeliveryPlan.NONE
        // 광고성 정보는 수신 동의가 없으면 설정과 무관하게 보내지 않는다(정보통신망법 §50, ADR-068).
        if (category == NotificationCategory.STRATEGY_MARKET && !marketingAgreed) return DeliveryPlan.NONE
        val (wantPush, wantEmail) = pref.channels(category)
        return DeliveryPlan(
            push = pref.pushEnabled && wantPush,
            email = pref.emailEnabled && wantEmail,
            emailIfPushMissed = pref.emailEnabled && wantPush && !wantEmail,
        )
    }
}
