# ADR-098: Watch Rule 지정가 결과 반영·규칙 기준 변경·관심종목 그룹 삭제 API

## Status
Accepted — [ADR-095](095-watch-rule-group-target-limit-equity-pct.md)의 두 결정을 바꾼다("PLACED는 이후에도 PLACED로 남는다",
"기준은 PATCH로 바꾸지 않는다"). 나머지 ADR-095 결정(그룹 대상·규칙 단위 쿨다운·지정가·계좌 %·그룹 삭제 트리거)은 그대로다.

## Context

ADR-095 "남긴 것" 세 가지. Watch Rule은 여전히 **모의투자 계좌 전용**이다([ADR-051](051-event-triggered-paper-orders.md)).

1. **PLACED 이후.** 지정가 발동이 미체결로 접수되면 발동 기록은 `PLACED`로 끝났다. 이후 스위퍼([ADR-074](074-paper-limit-orders.md))가
   체결하거나 사용자가 취소해도 기록은 그대로라 "이 규칙이 실제로 산 건가"를 주문 내역과 맞춰 봐야 했다. 고려한 방식:
   - (a) 화면이 주문 상태를 조인해 보여 준다 — 기록 자체는 계속 틀린 상태로 남고, 조회마다 matching 테이블을 읽는다.
   - (b) 커밋 후 비동기 리스너(@ApplicationModuleListener)로 반영 — 체결과 기록 사이에 창이 있고, 재시도·실패가 따로 생긴다.
   - (c) 체결·취소 트랜잭션 안의 **동기 리스너 + 조건부 UPDATE** — 채택. paper의 체결 기록(PaperExecutionListener)과 조건부 주문
     부모 연동(PaperConditionalParentListener)이 이미 같은 방식이다.
   남는 틈: 주문 접수(사가 트랜잭션)와 PLACED 기록(실행기의 별도 트랜잭션, ADR-051 — 거부 기록을 남기려고 나눴다)이 다른 트랜잭션이라,
   그 사이에 체결·취소가 끝나면 리스너의 UPDATE는 아직 없는 행을 보고 0행으로 끝난다.
2. **규칙 기준 변경.** ADR-095는 대상·주문 유형·수량 기준을 PATCH로 바꾸지 못하게 했다. 사용자는 규칙을 지우고 다시 만들어야 했고, 그러면
   발동 이력이 규칙과 끊기고 하루 한도·쿨다운이 새로 시작된다(한도를 비우는 우회가 된다).
3. **그룹 삭제 API.** 관심종목 그룹을 지우는 엔드포인트가 없었다(V92 트리거는 회원 탈퇴 연쇄에서만 불렸다).

## Decision

### 1. PLACED → FILLED | CANCELLED (V94)

```
watch_rule_executions
  status       + FILLED(접수했던 지정가가 이후 체결) + CANCELLED(체결 전 취소)
  resolved_at  TIMESTAMPTZ   CHECK: FILLED·CANCELLED ⇔ resolved_at 있음, FILLED ⇒ fill_price 있음
  idx_watch_rule_exec_placed_order (order_id) WHERE status = 'PLACED'

UPDATE watch_rule_executions SET status = 'FILLED', fill_price = ?, resolved_at = ?   WHERE order_id = ? AND status = 'PLACED'
UPDATE watch_rule_executions SET status = 'CANCELLED', reason = ?, resolved_at = ?    WHERE order_id = ? AND status = 'PLACED'
```

- **경로 A — 동기 리스너**(`WatchRuleOrderListener`, 같은 트랜잭션): `OrderFilledEvent`(출처 ADR-085가 `WATCH_RULE`인 것만) → FILLED,
  `OrderCancelledEvent`(출처가 없어 전부 — 부분 인덱스로 찾는다) → CANCELLED. 체결·취소가 롤백되면 전이도 롤백된다.
  `OrderCancelledEvent`에 선택 필드 `reason`을 더했다(사용자 취소·체결 시점 리스크 차단·보유 부족 — 기록의 reason이 된다).
- **경로 B — 기록 직후 대조**(`WatchRuleOrderOutcomes.reconcile`): 실행기가 PLACED를 저장한 뒤 `SELECT … FROM orders WHERE id = ? FOR UPDATE`로
  주문 행을 잠그고, 이미 FILLED(→ `avg_fill_price`)·CANCELLED/REJECTED(→ `reject_reason`)면 같은 조건부 UPDATE를 한다. 진행 중인 체결·취소가
  있으면 잠금에서 그 커밋을 기다린다. 실패해도 주문은 이미 나갔으므로 삼키고 경고만 남긴다.
- **정확히 한 번**: 체결과 취소는 주문 행 잠금(스위퍼 `SKIP LOCKED`, 취소 `FOR UPDATE`)으로 이미 하나만 일어나고, 어느 경로든 `status = 'PLACED'`
  조건이라 두 번째 전이는 0행이다. 잠금 순서는 두 경로 모두 orders → watch_rule_executions.
- watchrule은 matching 저장소를 쓰지 않고 `orders` 행을 잠그고 읽기만 한다. 모듈 의존에 `matching::api`(이벤트)를 더했다.
- **손익 이중 집계 없음**: 규칙 경유 손익(ADR-085)은 `paper_trades.origin`에서 계산한다. 이 전이는 발동 기록만 바꾸고 체결 기록은 체결
  리스너가 한 번 만든다. 발동권(쿨다운·하루 한도, ADR-077)도 건드리지 않는다 — ADR-095대로 PLACED 시점에 이미 센 것이 그대로다.
- 웹 발동 이력: "지정가 체결"(지정가 → 체결가, 결과 시각), "지정가 취소"(사유). 지정가 주문에 만료는 없다(ADR-074) — 만료 상태는 만들지 않았다.

### 2. PATCH로 기준 바꾸기 — 생성과 같은 검증

- `PATCH /api/watch-rules/{id}`에 `targetType`·`stockId`·`targetGroupId`·`orderType`·`sizeType`. 요청에 없는 값은 지금 값을 잇되, **기준이
  바뀌면 그 기준에 딸린 값은 잇지 않는다**(SHARES → EQUITY_PCT면 `equityPct`가 있어야 하고 `quantity`는 지워진다; LIMIT → MARKET이면 오프셋이
  지워진다; STOCK → GROUP이면 `targetGroupId`가 있어야 하고 종목은 지워진다).
- 합친 결과는 생성과 같은 함수(`validateOrderAndSize`·`validateTarget`)를 통과해야 한다 — V92 CHECK와 같은 조건. 대상이 바뀌면 존재·소유를
  다시 확인하고 남의 그룹은 없는 그룹과 같은 404다(security-review H6). 대상이 그대로면 확인하지 않는다(그룹이 지워진 규칙도 이름은 고친다).
- 그룹이 지워져 꺼진 규칙은 **대상을 바꾸면 다시 켤 수 있다**(ADR-095는 "새 규칙을 만든다"였다).
- 규칙 id가 그대로라 쿨다운·하루 한도·발동 이력이 이어진다.
- **발동과의 경합**: 실행기는 규칙을 잠금 없이 조회한 뒤 발동권을 잡는다. 발동권 SQL(`WatchRuleGuards.LOCK_RULE_SQL`, 규칙 행 `FOR UPDATE`)이
  잠근 행의 기준(`WatchRuleShapeValues`)을 함께 돌려주고, 실행기는
  - 대상이 조회 때와 다르면 이 원인을 SKIPPED("발동 직전 규칙 대상이 바뀌어…")로 남기고 발동권을 돌려준다,
  - 같으면 잠근 행의 주문 유형·수량 기준으로 주문을 정한다(조회 때의 엔티티가 아니라).

### 3. `DELETE /api/watchlists/groups/{id}`

- 소유자 조건으로 그룹 행을 `FOR UPDATE`로 잠그고(같은 그룹의 추가·이동과 줄을 세운다) 항목 id를 읽은 뒤 `DELETE FROM watchlist_groups`.
  항목은 FK `ON DELETE CASCADE`(V3), 대상 규칙은 V92 트리거가 같은 트랜잭션에서 끈다. 남의 그룹·없는 그룹은 같은 404.
- 지운 항목마다 검색 색인 삭제 이벤트(ADR-042)를 같은 트랜잭션에 발행한다.
- JPA cascade가 아니라 JDBC 한 문장 — 같은 SQL을 통합 테스트가 실제 Postgres에서 트리거와 함께 검증한다.
- 웹 /watchlist: 그룹 삭제 버튼 → 확인(종목 수, "이 그룹을 대상으로 한 Watch Rule은 꺼집니다 — 지금 켜진 규칙 N개", 규칙·기록은 남음).

## Reasons

- **같은 트랜잭션 + 조건부 UPDATE**는 이 코드베이스가 이미 쓰는 "주문 상태를 다른 기록으로 옮기는" 방식이고, 비동기 재시도·DLT가 따로 필요 없다.
- **기록 직후 대조**는 실행기의 트랜잭션 분리(ADR-051 — 거부 기록을 남기려고 나눴다)를 되돌리지 않고 남는 틈만 메운다. 주문 행 잠금은
  체결·취소가 이미 쓰는 잠금이라 새 잠금 순서가 생기지 않는다.
- **생성과 같은 검증 함수**를 PATCH에 쓰면 두 경로가 V92 CHECK와 어긋날 수 없다. 규칙을 지우고 다시 만드는 우회(한도 초기화)도 사라진다.
- **잠근 행의 기준으로 발동**하면 "PATCH 직후 이벤트가 옛 기준으로 주문"하는 창이 발동권 잠금 시점까지 줄어든다.

## Consequences

- watchrule이 `orders` 테이블 이름·컬럼(status·avg_fill_price·reject_reason·updated_at)을 안다(읽기·잠금만). 주문 테이블을 바꾸면 함께 고친다.
- 모든 주문 취소가 `watch_rule_executions`에 조건부 UPDATE를 한 번 돈다(부분 인덱스라 미체결 기록이 없으면 인덱스 조회 한 번).
- 리스너 UPDATE가 실패하면 체결·취소도 롤백된다(fail-closed, 체결 기록 리스너와 같은 성질).
- 대조가 실패하면(DB 오류) 그 기록은 PLACED로 남을 수 있다 — 주문·거래 내역이 진실이다. 경고 로그만 남는다.
- 발동권을 잡은 뒤 주문을 내기 전까지는 여전히 잠금 밖이다 — 그 사이의 PATCH는 다음 발동부터 적용된다.
- V94 인덱스는 `CONCURRENTLY` 없이 만든다(`watch_rule_executions`는 발동마다 한 줄 쓰는 낮은 쓰기량 테이블, 한 마이그레이션에 ALTER와 함께).
- `OrderCancelledEvent`에 선택 필드가 생겼다(기본 null — 기존 직렬화 이벤트와 호환).

## Revisit When

- 모의 지정가에 만료(GTD·당일 유효)가 생길 때 — EXPIRED 결과를 같은 방식으로 더한다.
- 부분 체결이 생길 때(유동성 모형) — FILLED를 체결 수량 누적으로 바꿔야 한다.
- 실행기를 한 트랜잭션으로 묶는 설계로 바뀌면 — 대조 경로가 필요 없어진다.
- 그룹 삭제 전에 걸린 규칙을 "다른 그룹으로 옮기기" 같은 일괄 UX가 필요할 때.
