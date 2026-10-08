# ADR-090: 퀀트 시그널 → 알림 이력 팬아웃

## Status
Accepted

## Context

/alerts의 "시그널" 탭은 필터만 있고 항상 비어 있었다(design-rollout-plan §/alerts). 포워드 테스트 신호는 `quant_signals`에 쌓이고,
룰셋 주인에게는 [ADR-082](082-notification-preferences-enforced-at-delivery.md)대로 `UserNotificationCommand(QUANT_SIGNAL)` 푸시가
나갔다. 하지만 알림 이력(`alert_histories`)에는 남지 않았다. 마켓 구독자는 푸시도 받지 못했다.

막힌 지점이 두 가지였다.

1. `alert_histories`의 주인은 `alert_rules`를 거쳐서만 정해진다(`rule_id NOT NULL`). 시그널은 사용자가 만든 규칙에서 나오지 않는다.
   "내 전략이거나 구독한 전략"이라는 관계에서 나온다([ADR-035](035-strategy-market-signal-access-control.md)).
2. 신호를 내는 쪽은 quant 모듈이고, 이력은 alert 모듈 소유다. 신호 트랜잭션에서 구독자 수만큼 행을 쓰면 quant가 alert 테이블에
   손대게 된다. 신호 평가 트랜잭션도 구독자 수에 비례해 길어진다.

고려한 대안:

- **A. 시그널 전용 테이블 + 화면에서 합치기** — 이력 검색(ES), 읽음 처리, 읽지 않음 수, 모두 읽음이 모두 두 갈래가 된다.
  [ADR-073](073-alert-read-state-and-rule-pause.md)의 읽음 모델을 다시 만들어야 한다.
- **B. 사용자마다 숨은 "시스템 규칙"을 만들어 `rule_id`를 채우기** — 스키마는 그대로다. 하지만 규칙 목록·워커 인메모리 인덱스
  ([ADR-044](044-alert-rule-in-memory-index.md))·규칙 수 통계에 가짜 규칙이 섞인다. 구독을 해지할 때 규칙도 정리해야 한다.
- **C. 신호 트랜잭션 안에서 바로 팬아웃** — 가장 즉각적이다. 하지만 위 2번 문제가 그대로 남는다.
- **D. `alert_histories`가 주인을 직접 갖게 하고, 신호 이벤트를 받은 alert 모듈이 새 트랜잭션에서 팬아웃** — 채택.

## Decision

**스키마(V87)**

- `alert_histories.rule_id`를 NULL 허용으로 바꾼다. `user_id`, `category`, `dedup_key` 열을 더한다.
- `CHECK`로 한 행의 주인은 정확히 한 길로만 정한다. 규칙 행은 `rule_id`만 갖는다. 사용자 행은 `user_id`·`category`·`dedup_key`를 모두 갖는다.
  worker `AlertDispatcher`의 INSERT는 바뀌지 않는다.
- 유니크 인덱스 `(user_id, dedup_key) WHERE user_id IS NOT NULL`. 시그널의 키는 `quant-signal:{quant_signals.id}`다. 즉 사용자당 신호 하나에 한 행이다.

**팬아웃(`alert.application.QuantSignalAlertFanout`)**

```
ForwardTestService.evaluateOne (신호 트랜잭션)
  └ quant_signals INSERT + publish QuantSignalEmittedEvent ──► event_publication
                                                                  │ 커밋 후 (@ApplicationModuleListener: 비동기, 새 트랜잭션)
QuantSignalAlertFanout.on ◄───────────────────────────────────────┘
  audience = StrategySignalAccess.audienceOf(ruleSetId)   // 주인 + 지금 구독자, 탈퇴자 제외, 룰셋 없으면 아무도
  for user in audience (한 트랜잭션):
    INSERT alert_histories … ON CONFLICT (user_id, dedup_key) DO NOTHING RETURNING id
    새로 들어간 행만 → SearchIndexEvent(alert_histories, ruleType=QUANT_SIGNAL)   (ADR-042 아웃박스)
                     → UserNotificationCommand(category=QUANT_SIGNAL, dedupKey=quant-signal:{id}:u{user})  (ADR-065 아웃박스)
```

- 이력 행, 색인 이벤트, 알림 명령을 **한 트랜잭션**에 쓴다. 두 이벤트는 `@Externalized`라 같은 `event_publication`에 기록됐다가
  커밋 후 Kafka로 나간다. DB 밖으로 따로 쓰는 경로(이중 쓰기)는 없다.
- 리스너가 실패하면 발행 기록이 미완료로 남는다. 그러면 `OutboxResubmissionConfig`가 5분 뒤 다시 보낸다. 재전달돼도 이미 있는 행은
  건너뛴다. 이벤트도 새로 들어간 행에만 발행한다. 따라서 행·푸시·색인이 모두 한 번이다.
- **발송 여부는 여기서 정하지 않는다.** 명령의 `category=QUANT_SIGNAL`을 보고 worker가 사용자 알림 설정을 적용한다(ADR-082). 이력은 설정과 무관하게
  남는다. 알림을 꺼도 알림함에서는 볼 수 있다. 행의 `delivery_status`는 `QUEUED`다. 발송 결과는 이 행에 되돌려 쓰지 않는다(ADR-065와 같음).
- 룰셋 주인에게 가던 알림도 이 경로로 옮긴다. `ForwardTestService`는 더 이상 `UserNotificationCommand`를 직접 내지 않는다.
  신호 이벤트에는 문구용 `price`·`evalDate`를 싣는다. 이 필드가 없던 옛 발행 기록이 재전달될 수 있어 기본값은 null이다.
- 모듈 의존: `alert → quant::api, quant::events`. quant는 alert를 모른다.

**읽기 경로**

- 소유 판정 하나로 통일한다: `ah.user_id = ? OR ah.rule_id IN (SELECT id FROM alert_rules WHERE user_id = ?)`.
  읽음, 모두 읽음, 읽지 않음 수, 통계, ES 재색인이 모두 이 판정을 쓴다. 남의 이력은 없는 이력과 같은 404다(security-review H6).
- ES 문서의 `ruleId`는 null을 허용한다. `ruleType=QUANT_SIGNAL`이다. 웹은 이 값을 "시그널" 분류로 보여준다.

**퀀트랩 상단 집계** — `GET /api/quant/signals/summary`가 `{todaySignals, activeSubscriptions, date}`를 돌려준다.

- "오늘"은 KST 달력일이다. 경계는 Kotlin에서 `Instant`로 계산해 바인딩한다.
- 신호는 피드와 같은 접근 규칙(내 룰셋 + 구독 전략)으로 센다.

## Reasons

- 알림 이력의 읽기 기능(검색, 읽음, 모두 읽음, 통계)을 그대로 쓴다. 바뀌는 것은 소유 판정 한 줄이다.
- 신호 평가 트랜잭션은 구독자 수와 무관하게 짧다. 팬아웃이 실패해도 신호 자체는 이미 커밋돼 있다.
- 받을 사람을 신호 시점이 아니라 **적재 시점**에 정한다. 해지한 구독자는 받지 않는다. 이미 받은 이력은 그 사람에게 남는다.
- 멱등의 근거가 사전 조회가 아니라 유니크 인덱스다. 동시 재전달 10개로 통합 테스트했다.

## Consequences

- 구독자가 많으면 한 트랜잭션이 커진다. 사용자당 행 1개와 이벤트 2개가 붙는다.
  수천 명까지는 문제없다. 그 이상이면 묶음 단위로 나눠야 한다. 멱등이라 묶음 단위로 나눠도 안전하다.
- `alert_histories`의 주인을 정하는 길이 두 가지다. 새 쿼리는 반드시 위 소유 판정을 써야 한다. `JOIN alert_rules`만 쓰면 시그널 행이 빠진다.
- 시그널 행의 `delivery_status`(`QUEUED`)는 실제 발송 결과를 말하지 않는다. 통계의 발송 성공률에서 빠진다.
- 이 기능 전에 난 신호는 이력에 없다(백필하지 않음).
- 웹 시그널 탭은 최근 이력 50건 안에서 거른다(다른 탭과 같음).

## Revisit When

- 한 전략의 구독자가 1만 명을 넘거나, 팬아웃 트랜잭션 시간이 알람 기준을 넘을 때 — 묶음 팬아웃이나 구독자 커서로 바꾼다.
- 실전 자동 운용 신호 등 규칙 없는 알림 종류가 더 생길 때 — `category`·`dedup_key`로 같은 길을 쓴다. 웹 분류를 늘린다.
- 발송 결과를 알림함에 보여줘야 할 때 — worker가 `historyId`(명령 data에 이미 있음)로 상태를 되돌려 쓰게 한다.
