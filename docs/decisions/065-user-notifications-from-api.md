# ADR-065: api 사용자 알림 경로 — 조건부 주문 발동 실패부터

## Status
Accepted

## Context

조건부 주문은 발동에 실패하면 재시도하지 않고 `FAILED`로 끝난다([ADR-032](032-conditional-orders.md)). 같은 조건이 유지되는 동안
매 틱마다 실패한 요청을 다시 쏘지 않기 위해서다. 그런데 이 결정에는 전제가 하나 숨어 있었다. 사용자가 실패를 안다는 것이다.
실제로는 아무도 알려주지 않았다. 사용자는 화면을 열기 전까지 스탑로스가 아직 걸려 있다고 믿는다.

[ADR-056](056-brokerage-order-unknown-outcome.md) 중복 가드가 이 공백을 키웠다. 같은 종목·방향의 결과 불명 주문이 있으면 새 주문을
거부하는데, 조건부 주문 발동도 여기 걸린다. 결과 불명은 보통 증권사 장애 중에 생긴다. 즉 손절이 가장 필요한 순간에 손절이
조용히 사라진다. 이 경로 말고도 `FAILED`가 되는 길은 더 있다. 증권사 거부, 리스크 게이트, 그리고 리퍼가 미전송으로 확정하는 경우다.

그런데 api에는 사용자에게 무언가를 보내는 경로가 아예 없었다. 푸시(Expo)와 이메일 발송은 worker의 `AlertDispatcher`에만 있고,
그마저 알림 규칙(`alert_rules`) 모양으로 묶여 있다(`notify.commands` 메시지 = ruleId·조건 JSON).

고려한 대안:
1. **api가 Expo를 직접 호출** — 가장 짧다. 하지만 외부 I/O가 발동 스레드(`conditionalOrderExecutor`) 안에서 돈다.
   [ADR-044](044-alert-rule-in-memory-index.md)가 평가와 발송을 나눈 이유가 바로 이것이다. 발송 코드(서킷브레이커, 이메일 폴백)도 두 벌이 된다.
2. **`notify.commands`에 실어 보내기** — 토픽은 재사용할 수 있다. 그러나 메시지와 `AlertDispatcher`가 알림 규칙 전용이다.
   alert_histories 기록, 룰별 10분 쿨다운, 룰 상태 갱신이 다 따라온다. 사건 하나를 정확히 한 번 알리는 의미와도 맞지 않는다.
3. **api 아웃박스 → 새 토픽 `notify.user` → worker 발송** — 채택.

## Decision

- api에 범용 명령 `UserNotificationCommand(userId, title, body, dedupKey, data)`를 둔다(`common/notification`). 이 명령에는
  `@Externalized("notify.user::#{#this.userId}")`가 붙는다. **상태 변경과 같은 트랜잭션 안에서** 발행한다. 그러면 Modulith
  event_publication(아웃박스)에 기록되고, 커밋 후 외부화된다. 실패한 건은 5분마다 재전송된다(기존 `OutboxResubmissionConfig`).
- `ConditionalOrderFailures.markFailed()`가 발동 실패를 닫는 **유일한 길**이다. 평가기와 리퍼가 모두 이것을 부른다.
  - `UPDATE … WHERE status = 'TRIGGERED' RETURNING`으로 닫고, 행이 바뀐 경우에만 알림을 발행한다. 평가기와 리퍼가 겹쳐도
    알림은 한 번이다.
  - `dedupKey = conditional-order-failed:{id}`.
  - 중복 가드는 전용 예외 `UnresolvedOrderInProgressException`을 던진다. 그래서 알림이 "어느 주문(#n) 때문에 막혔는지"를
    말할 수 있다.
- worker에 `UserNotifyKafkaConsumer`(역할 `notify|alert|all`, `@RetryableTopic` 3회 + DLT)와 `UserNotificationDispatcher`를 둔다.
  - Redis `SETNX notify:user:sent:{dedupKey}`(2일)로 사건 단위 중복을 제거한다. 발송이 예외로 실패하면 표시를 지워
    재시도가 다시 보낼 수 있게 한다.
  - 등록 기기가 있으면 Expo 푸시를 보낸다. 기기가 없거나 푸시가 어느 기기에도 닿지 않으면(서킷 OPEN 포함) 이메일로 보낸다.
- 토픽 `notify.user`(키 userId, 1일)와 재시도 패밀리는 api `KafkaTopicConfig`가 선언한다([ADR-040](040-kafka-topic-declaration.md)).

## Reasons

- 실패 기록과 알림이 원자적이다. FAILED는 남았는데 알림이 사라지는 경우가 없다(Kafka 장애 시 아웃박스가 재전송한다).
  반대로 알림만 나가고 상태가 남지 않는 경우도 없다.
- 발송 I/O가 api 스레드를 잡지 않는다. Expo·SMTP 장애가 주문 경로로 번지지 않는다(ADR-044와 같은 이유).
- 명령이 범용이라 다음 알림이 같은 길을 쓴다. 백로그의 "실시세를 잃은 조건부 주문 알림"(ADR-060)과 AI 제안 알림이 그 예다.

## Consequences

- api → worker 방향의 첫 Kafka 계약이다. 메시지 모양이 두 곳(api `UserNotificationCommand`, worker `UserNotificationMessage`)에 있고,
  공유 모듈이 없어 손으로 맞춘다. worker 테스트가 api JSON 예시를 읽어 이를 지킨다.
- 이력 테이블이 없다. 무엇을 보냈는지는 로그와 `dlt_messages_total{topic="notify.user"}`로만 안다. 인앱 알림함이 필요해지면
  그때 테이블을 둔다.
- 사용자 알림 설정(`notif:pref:*`)을 보지 않는다. 보호가 사라졌다는 사실은 끌 수 있는 알림이 아니라고 봤다. 설정을 존중해야 하는
  알림 종류가 생기면 명령에 분류를 더한다.
- 재무장(해소 뒤 같은 조건으로 다시 걸지 묻기)은 하지 않는다. 사용자가 직접 다시 등록한다.

## Revisit When

- 인앱 알림함·읽음 처리가 필요해질 때 — 이력 테이블과 함께 api 쪽 저장을 검토한다.
- 알림 종류가 늘어 사용자 설정을 존중해야 할 때.
- 결과 불명 해소 뒤 자동 재무장 요구가 생길 때 — 그 경우 FAILED 대신 별도 상태가 필요하다.
