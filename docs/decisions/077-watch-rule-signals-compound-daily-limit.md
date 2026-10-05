# ADR-077: Watch Rule 확장 — 전략 신호 트리거, 복합 조건, 하루 최대 발동

## Status
Accepted

## Context

[ADR-051](051-event-triggered-paper-orders.md)의 watch rule은 탐지 이벤트 3종(거래량 급증·급등·급락) 하나에만 반응한다.
화면에는 세 가지가 비어 있었다: ① 퀀트랩 전략 신호를 감지 조건으로 쓰기, 규칙 이름, 복합 조건 ② 하루 최대 발동과
카드의 "오늘 발동 / 한도". 모두 모의투자 계좌 전용이다(실브로커 자동 실행은 여전히 범위 밖 — ADR-025/036).

### 결정해야 했던 것

1. **전략 신호를 watchrule에 어떻게 전달하나**
   - (a) watchrule이 `quant_signals`를 주기적으로 폴링(커서 보관) — 다른 모듈 테이블을 직접 읽고 커서 상태가 생긴다.
   - (b) quant가 신호 저장과 같은 트랜잭션에서 도메인 이벤트를 발행하고 watchrule이 구독 — Modulith 이벤트 발행 기록으로
     재전달이 보장된다.
2. **복합 조건의 의미** — 임의의 AND/OR 트리(지표·가격·이벤트)는 규칙 평가기를 새로 만드는 일이다. 반면 이벤트 규칙의
   발동 시점에 "다른 탐지 이벤트도 최근 N분 안에 있었나"는 기존 `stock_events`만으로 판정된다.
3. **하루 한도의 동시성** — 실행기는 의도적으로 트랜잭션 밖이다(ADR-051: 거부 기록을 남기려고). "오늘 몇 번 체결됐나
   세고 → 주문"은 두 이벤트가 동시에 오면 둘 다 통과한다.

## Decision

1. **(b) 도메인 이벤트.** `quant::events.QuantSignalEmittedEvent(signalId, ruleSetId, stockId, direction, signalTime)`를
   `ForwardTestService.evaluateOne` 트랜잭션에서 발행한다. watchrule은 `@Async @TransactionalEventListener` +
   `@Transactional(NOT_SUPPORTED)`로 받는다 — `@ApplicationModuleListener`(REQUIRES_NEW)를 쓰지 않는 이유는, 그 트랜잭션
   안에서 주문이 거부되면 rollback-only가 되어 REJECTED 기록이 커밋되지 못하고 같은 거부가 무한 재전달되기 때문이다.
   - 규칙: `event_type = 'QUANT_SIGNAL'` + `rule_set_id` + `signal_direction`(BUY·SELL). 등록 때와 **발동 때 모두**
     `StrategySignalAccess.canAccess`(소유자 또는 구독자 — ADR-035와 같은 기준, `quant::api`)를 확인한다. 구독을 끊으면 SKIPPED.
   - 멱등: 발동 기록에 `quant_signal_id` + 유니크 `(watch_rule_id, quant_signal_id)`, 주문 멱등 키 `WR:{ruleId}:Q{signalId}`.
     접두 `WR:`을 유지해 거래 내역 "경로"가 그대로 Watch Rule로 읽힌다.
2. **복합 조건 = 동반 이벤트(AND).** `required_event_types`(쉼표 구분, 주 이벤트와 다른 지원 유형) +
   `condition_window_sec`(1분~24시간, 기본 30분). 주 이벤트 시각 앞 창 안에 각 유형이 `stock_events`에 하나 이상 있어야 발동하고,
   아니면 SKIPPED("복합 조건 미충족: … 없음"). 전략 신호 규칙에는 쓰지 않는다.
3. **하루 최대 발동 = 조건부 UPSERT 슬롯.**
   ```sql
   INSERT INTO watch_rule_daily_counts (watch_rule_id, day, executed) VALUES (?, ?, 1)
   ON CONFLICT (watch_rule_id, day) DO UPDATE SET executed = executed + 1 WHERE executed < :limit
   RETURNING executed            -- 0행이면 한도 도달 → SKIPPED
   ```
   주문 직전에 슬롯을 잡고, 체결되지 않으면(거부·인프라 예외) 돌려준다. day는 KST 날짜. 한도가 없어도 슬롯을 세서
   카드의 "오늘 발동"이 서버가 집행하는 바로 그 카운터가 된다(`WatchRuleResponse.todayExecutions`).
4. 규칙 이름 `name`(100자). PATCH는 `name`(빈 문자열 = 지움)·`dailyLimit`(0 = 해제)을 바꿀 수 있다.

## Reasons

- (b)는 신호가 롤백되면 이벤트도 없고, 커밋되면 발행 기록이 재전달을 책임진다. watchrule이 quant 테이블과 커서를 알 필요가 없다.
- 동반 이벤트 AND는 사용자가 실제로 원하는 "거래량이 터지면서 가격도 뛸 때"를 표현하면서 새 평가기 없이 인덱스
  `(stock_id, event_time)` 한 번으로 끝난다.
- UPSERT 한 문장은 행 잠금 위에서 WHERE를 최신 값으로 재평가하므로 동시 발동이 한도를 넘을 수 없다 — 실행기를
  트랜잭션으로 감싸지 않고도(ADR-051의 결정을 유지하면서) 정합성을 얻는다.

## Consequences

- 슬롯을 잡은 뒤 프로세스가 죽으면 그날 한도가 1 적게 남는다(돈이 덜 움직이는 안전한 방향). 체결 후 기록 전에 죽어
  재전달되면 멱등 키가 체결을 하나로 되돌리지만 슬롯은 한 번 더 잡힐 수 있다 — 역시 보수적 방향.
- watchrule이 `quant::api`·`quant::events`에 의존한다. quant는 watchrule을 모른다(순환 없음).
- `watch_rule_executions.stock_event_id`가 nullable이 됐다(CHECK: 이벤트·신호 중 정확히 하나).
- 쿨다운 판정은 여전히 "조회 후 판단"이라 동시 이벤트 두 개가 쿨다운을 함께 통과할 수 있다. 하루 한도는 이 창을 닫지만
  쿨다운 자체는 이번 범위가 아니다.

## Revisit When

- 지표·가격 조건을 섞은 일반 조건 트리가 필요할 때 — 퀀트랩 RuleEvaluator를 재사용하는 별도 설계.
- 여러 종목(관심종목 그룹)에 하나의 규칙을 걸 때 — 슬롯·멱등 키의 단위를 다시 정한다.
- 쿨다운도 원자적으로 집행해야 할 때 — 같은 슬롯 테이블에 `last_executed_at`을 두는 방향.
