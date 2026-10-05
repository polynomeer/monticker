package com.monticker.api.common.notification

import org.springframework.modulith.events.Externalized

/**
 * ADR-065 — api가 사용자에게 보내는 알림(푸시, 없으면 이메일). 발송은 worker가 한다(ADR-044의 평가·발송 분리와 같은 이유 —
 * 외부 I/O가 주문 스레드를 잡지 않게). 상태 변경과 **같은 트랜잭션 안에서** 발행해야 한다: Modulith가 event_publication에
 * 남겼다가 커밋 후 `notify.user`로 외부화하고, 실패하면 5분 뒤 재전송한다. 트랜잭션 밖에서 발행하면 외부화 리스너가
 * 돌지 않아 조용히 사라진다.
 *
 * [dedupKey]는 사건 하나에 하나 — 재전송·재시도로 같은 명령이 두 번 와도 worker가 한 번만 보낸다.
 * 키 = userId: 한 사용자의 알림 순서가 보장된다.
 */
@Externalized("notify.user::#{#this.userId}")
data class UserNotificationCommand(
    val userId: Long,
    /** ADR-082 — 설정을 적용할 종류. 기본값이 없다: 새 알림은 끌 수 있는지부터 정해야 한다. */
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val dedupKey: String,
    val data: Map<String, Any> = emptyMap(),
)
