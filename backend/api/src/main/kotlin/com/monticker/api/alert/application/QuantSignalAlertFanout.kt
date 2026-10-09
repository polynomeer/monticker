package com.monticker.api.alert.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.alert.infrastructure.UserAlertHistoryRepository
import com.monticker.api.common.notification.NotificationCategory
import com.monticker.api.common.notification.UserNotificationCommand
import com.monticker.api.common.search.SearchIndexEvent
import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.quant.events.QuantSignalEmittedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * ADR-090 — 퀀트 시그널 하나를 그 신호를 볼 수 있는 사람(룰셋 주인 + 마켓 구독자, ADR-035) 각자의 알림 이력에 적재하고 알린다.
 *
 * - 신호 트랜잭션이 커밋된 뒤 Modulith 이벤트 발행 기록(event_publication)을 거쳐 실행된다(@ApplicationModuleListener =
 *   AFTER_COMMIT + 새 트랜잭션 + 비동기). 실패하면 미완료로 남아 OutboxResubmissionConfig가 다시 보낸다.
 * - 이력 행·ES 색인 이벤트(ADR-042)·알림 명령(ADR-065)을 **한 트랜잭션에서** 쓴다. 두 이벤트는 @Externalized라
 *   같은 event_publication에 기록됐다가 커밋 후 외부화된다 — 행만 남고 알림이 사라지거나 그 반대인 경우가 없다.
 * - 멱등: (사용자, `quant-signal:{signalId}`) 유니크. 재전달되면 이미 있는 사용자는 건너뛰고(행·이벤트 없음),
 *   새로 볼 수 있게 된 사용자가 있으면 그 사람만 받는다.
 * - 발송 여부는 여기서 정하지 않는다: 명령의 category=QUANT_SIGNAL을 worker가 사용자 알림 설정으로 거른다(ADR-082).
 *   이력은 설정과 무관하게 남는다 — 알림을 꺼도 알림함(/alerts 시그널 탭)에서는 볼 수 있어야 한다.
 * - 운영 스위치 `app.quant-signal-push.enabled=false`(QUANT_SIGNAL_PUSH_ENABLED)면 이력·색인은 그대로 쓰고 알림 명령만 내지 않는다.
 *   끄기 전에 이미 나간 명령은 멈추지 않고, 꺼 둔 동안의 신호는 다시 켜도 소급 발송하지 않는다.
 */
@Component
class QuantSignalAlertFanout(
    private val access: StrategySignalAccess,
    private val histories: UserAlertHistoryRepository,
    private val events: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
    @Value("\${app.quant-signal-push.enabled:true}") private val pushEnabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CATEGORY = "QUANT_SIGNAL"
        /** 이력의 전달 상태 — 발송은 worker가 설정을 보고 정하며 결과를 이 행에 되돌려 쓰지 않는다(ADR-065: 피드백 없음). */
        const val DELIVERY_STATUS = "QUEUED"
        fun historyKey(signalId: Long) = "quant-signal:$signalId"
        fun notifyKey(signalId: Long, userId: Long) = "quant-signal:$signalId:u$userId"
    }

    @ApplicationModuleListener
    fun on(event: QuantSignalEmittedEvent) {
        fanOut(event)
    }

    /** 새로 적재한 사용자 수를 돌려준다(재전달이면 0). */
    fun fanOut(event: QuantSignalEmittedEvent): Int {
        val audience = access.audienceOf(event.ruleSetId)
        if (audience == null) {
            log.info("[QuantSignalAlert] 룰셋이 없어 적재하지 않음: signalId={} ruleSetId={}", event.signalId, event.ruleSetId)
            return 0
        }
        val buy = event.direction == "BUY"
        val side = if (buy) "매수" else "매도"
        val detail = listOfNotNull(
            event.price?.let { "종가 ${String.format(java.util.Locale.KOREA, "%,.0f", it)}원" },
            event.evalDate?.toString(),
        ).joinToString(", ")
        val message = "${audience.ruleSetName} $side 신호" + if (detail.isEmpty()) "" else " ($detail)"
        val payload = objectMapper.writeValueAsString(mapOf(
            "signalId" to event.signalId, "ruleSetId" to event.ruleSetId, "direction" to event.direction,
            "price" to event.price, "evalDate" to event.evalDate?.toString(),
        ))

        var inserted = 0
        for (userId in audience.userIds) {
            val historyId = histories.insertIfAbsent(
                userId = userId, category = CATEGORY, dedupKey = historyKey(event.signalId), stockId = event.stockId,
                triggeredAt = event.signalTime, message = message, deliveryStatus = DELIVERY_STATUS, payloadJson = payload,
            ) ?: continue
            inserted++
            events.publishEvent(SearchIndexEvent.index(AlertHistoryIndexer.INDEX, historyId.toString(), mapOf(
                "ruleId"         to null,
                "userId"         to userId,
                "stockId"        to event.stockId,
                "ruleType"       to CATEGORY,
                "message"        to message,
                "deliveryStatus" to DELIVERY_STATUS,
                "triggeredAt"    to event.signalTime.toEpochMilli(),
            )))
            if (!pushEnabled) continue
            val owner = userId == audience.ownerId
            events.publishEvent(UserNotificationCommand(
                userId = userId,
                category = NotificationCategory.QUANT_SIGNAL,
                title = "${audience.ruleSetName} $side 신호",
                body = (if (owner) "포워드 테스트에서" else "구독 중인 전략에서") + " $side 신호가 났습니다" +
                    (if (detail.isEmpty()) "" else "($detail)") + ". 모의 신호이며 실제 주문은 나가지 않았습니다.",
                dedupKey = notifyKey(event.signalId, userId),
                data = mapOf(
                    "type" to CATEGORY, "ruleSetId" to event.ruleSetId, "stockId" to event.stockId,
                    "direction" to event.direction, "historyId" to historyId,
                ),
            ))
        }
        log.info("[QuantSignalAlert] signalId={} ruleSetId={} audience={} newlyRecorded={} push={}",
            event.signalId, event.ruleSetId, audience.userIds.size, inserted, pushEnabled)
        return inserted
    }
}
