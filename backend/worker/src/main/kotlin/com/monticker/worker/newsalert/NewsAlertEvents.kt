package com.monticker.worker.newsalert

import com.monticker.worker.notification.NotificationCategory
import com.monticker.worker.notification.UserNotificationMessage
import java.time.Instant

enum class NewsAlertKind { NEWS, DISCLOSURE }

/**
 * ADR-100 — 수집기가 새 기사·공시를 넣은 **그 트랜잭션 안에서** 발행한다. 외부화하지 않는 worker 내부 이벤트다: Modulith가
 * worker_outbox.event_publication에 기록하고 커밋 후 [NewsAlertFanout]에 넘긴다(실패하면 미완료로 남아 재전송).
 *
 * 발행 기록에 JSON으로 남았다가 재전송 때 다시 읽힌다 — 필드를 더할 때는 기본값을 둔다(이전 버전이 남긴 기록도 읽혀야 한다).
 *
 * @property sourceId 뉴스는 news_articles.id, 공시는 stock_events.id
 * @property publishedAtMillis 뉴스 발행 시각 / 공시 접수일(KST 0시)
 */
data class NewsAlertCandidateEvent(
    val kind: NewsAlertKind,
    val sourceId: Long,
    val stockId: Long,
    val title: String,
    val publishedAtMillis: Long,
    val importanceScore: Int? = null,
    val url: String? = null,
    val source: String? = null,
) {
    fun publishedAt(): Instant = Instant.ofEpochMilli(publishedAtMillis)
    /** 알림 이력의 사건 키 — 사용자당 한 행(uq_alert_histories_user_dedup) */
    fun historyKey(): String = "${kind.name.lowercase()}:$sourceId"
}

/**
 * ADR-100 — 이력 행을 새로 넣은 사용자 한 명에게 알림을 보내라는 worker 내부 이벤트. 팬아웃 트랜잭션 안에서 발행되고 커밋 후
 * [NewsAlertDelivery]가 같은 JVM에서 [com.monticker.worker.notification.UserNotificationDispatcher]로 보낸다.
 * notify.user(Kafka)를 거치지 않는 이유: 롤링 배포 중 NEWS를 모르는 이전 worker 소비자가 받으면 끌 수 없는 종류로 읽어
 * (`NotificationCategory.fromWire`) 설정·방해 금지 시간을 무시하고 보낸다. 같은 JVM이면 버전이 어긋날 수 없다.
 */
data class NewsAlertNotifyEvent(
    val userId: Long,
    val historyId: Long,
    val title: String,
    val body: String,
    val dedupKey: String,
    val data: Map<String, Any> = emptyMap(),
) {
    fun toMessage() = UserNotificationMessage(
        userId = userId, title = title, body = body, dedupKey = dedupKey, data = data,
        category = NotificationCategory.NEWS.name,
    )
}
