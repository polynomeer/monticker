# ADR-095: Watch Rule 대상 그룹·지정가 오프셋·계좌 % 수량

## Status
Accepted

## Context

[design-rollout-plan](../design-rollout-plan.md) P2 — /watch-rules의 "관심종목 그룹 단위, 주문 유형·금액(계좌 %)"은 화면에만 있었다
("+ 종목 그룹" 준비 중, 주문 유형은 시장가 고정, 수량은 주 수만). Watch Rule은 여전히 **모의투자 계좌 전용**이다
([ADR-051](051-event-triggered-paper-orders.md)) — 이번 확장도 `matching::submit`만 부르고 `brokerage`에 의존하지 않는다.

정해야 했던 것:

1. **그룹 규칙의 단위.** (a) 규칙을 만들 때 그룹의 종목마다 종목 규칙을 복제 — 그룹 구성이 바뀌면 규칙이 어긋나고, 하나를 끄려면
   N개를 꺼야 한다. (b) 규칙이 그룹을 가리키고 **평가 시점**에 이벤트 종목이 그 그룹에 있는지 본다 — 채택.
2. **그룹 규칙의 쿨다운·하루 한도.** 규칙 단위 vs (규칙, 종목) 단위. ADR-077의 발동권(`WatchRuleGuards.claimFiring`, 규칙 행
   `FOR UPDATE` + 하루 슬롯 UPSERT)은 규칙 단위다. (규칙, 종목) 단위로 바꾸려면 잠금 행·슬롯 키·`last_fired_at`을 모두 종목별로
   쪼개야 하고, 그룹이 커질수록 "한 규칙이 하루에 낼 수 있는 주문 수"의 상한이 사라진다.
3. **그룹 삭제.** FK `ON DELETE CASCADE`면 규칙과 발동 기록이 조용히 사라지고, `SET NULL`이면 대상 없는 규칙이 남는다.
4. **지정가 가격의 기준과 경계.** 발동 이벤트에는 가격이 없다(퀀트 신호에는 그날 종가만 있다).
5. **계좌 % 수량의 기준.** 현금 vs 평가자산, 계산 시점(등록 vs 발동).
6. **지정가 주문의 멱등.** `OrderSubmitter.submitLimit`에는 멱등 키가 없었다 — 아웃박스 재전달이 같은 지정가를 두 번 걸 수 있다.

## Decision

### 스키마 — V92

```
watch_rules
  target_type      STOCK | GROUP (기본 STOCK)      CHECK: STOCK ⇔ stock_id만, GROUP ⇔ target_group_id만
  target_group_id  BIGINT (FK 없음)                stock_id는 NULL 허용으로
  order_type       MARKET | LIMIT (기본 MARKET)    CHECK: LIMIT ⇔ limit_offset_bps ∈ [-1000, 1000]
  limit_offset_bps INTEGER
  size_type        SHARES | EQUITY_PCT (기본 SHARES) CHECK: SHARES ⇔ quantity, EQUITY_PCT ⇔ equity_pct ∈ [1, 25]
  equity_pct       NUMERIC(5,2)                    quantity는 NULL 허용으로
  idx_watch_rules_group_event (target_group_id, event_type) WHERE GROUP AND is_active

watch_rule_executions
  stock_id     발동 종목(기존 행은 규칙의 종목으로 백필)
  limit_price  지정가 접수의 지정가
  status       + PLACED (지정가 접수, 미체결)

trigger trg_watchlist_group_delete_disables_rules  AFTER DELETE ON watchlist_groups
  → 그 그룹을 대상으로 한 활성 규칙을 is_active = false (규칙·기록은 남는다)
```

기존 규칙은 기본값(STOCK·MARKET·SHARES)이라 동작이 바뀌지 않는다.

### 1. 그룹 대상 — 평가 시점 구성, 소유자 조건

이벤트(또는 퀀트 신호)마다 `WatchRuleRepository.findActiveForEvent/ForSignal`이 **종목 규칙 + 지금 이 종목이 든 그룹을 대상으로 한
규칙**을 한 번에 찾는다(`UNION ALL`, `WatchRuleQueries`). 그룹 조건은 항상 `watchlist_groups.user_id = watch_rules.user_id`다 —
남의 그룹 id를 가리키는 규칙이 DB에 있어도 발동하지 않는다. 생성 때 서비스가 소유를 확인하고, 남의 그룹은 없는 그룹과 같은
404·같은 메시지다([security-review H6](../security-review.md)). watchrule은 watchlist 모듈에 의존하지 않고 두 테이블을 읽기 전용
JDBC로 본다(사가가 `portfolio_positions`를 읽는 것과 같은 수준).

그룹 규칙의 발동에서 종목이 필요한 모든 곳(복합 조건의 동반 이벤트 조회, 시세, 주문)은 **원인(이벤트·신호)의 종목**을 쓴다.

### 2. 쿨다운·하루 한도는 규칙 단위, 중복은 (규칙, 이벤트)

- 그룹 규칙의 쿨다운·하루 최대 발동은 **그룹 전체에 대해** 센다. 쿨다운 10분인 그룹 규칙은 그룹 안 어느 종목이든 10분에 한 번,
  하루 한도 3이면 그룹 전체에서 하루 3번이다. ADR-077의 claim/release를 그대로 쓴다 — 잠금 순서·compare-and-set 반환 모두 변경 없음.
- "한 이벤트가 두 번 발동하지 않는다"는 기존 장치로 충분하다: 이벤트·신호는 종목 하나에 속하고, (그룹, 종목)이 유니크(V3)라
  조회 결과에 같은 규칙이 두 번 나오지 않으며, (규칙, 이벤트)·(규칙, 신호) 유니크(V49·V69)와 주문 멱등 키 `WR:{ruleId}:{eventId}`가
  그대로 (규칙, 종목, 이벤트) 단위다. 새 dedup 키를 만들지 않았다.
- 발동 기록에 `stock_id`를 남겨 그룹 규칙이 어느 종목에서 발동했는지 이력에 보인다.

### 3. 그룹 삭제 → 규칙 중지

DB 트리거가 그룹이 지워지는 **모든 경로**(화면, 회원 탈퇴 연쇄 등)에서 대상 규칙을 끈다. 규칙과 발동 기록은 남고, 응답의
`targetGroupMissing = true`로 화면이 "대상 그룹 삭제됨"을 보인다. 다시 켜기는 서비스가 거부한다(400 — 대상이 없다). 사용자는 새
규칙을 만든다. 평가 쿼리도 그룹·소유자를 조인하므로 트리거가 없더라도 지워진 그룹으로는 발동하지 않는다(이중 방어).

### 4. 지정가 — 발동 시점 최신 1분봉 종가 ± bps

```
지정가 = 발동 가격 × (1 + limit_offset_bps / 10,000)      limit_offset_bps ∈ [-1000, 1000] (±10%)
반올림 = 매수 내림 · 매도 올림 (요청보다 공격적이지 않게), 자릿수 = 발동 가격의 자릿수(최대 4)
발동 가격 = 사가와 같은 출처(candles_1m 최신 종가). 없거나 CandleFreshness.MAX_AGE(5분)보다 오래되면 SKIPPED
```

주문은 `OrderSubmitter.submitLimit(origin = WATCH_RULE(ruleId), idempotencyKey = WR:…)` — 손으로 낸 지정가와 같은 ADR-074 경로다:
교차하면 즉시 체결(EXECUTED), 아니면 미체결(PLACED)로 접수되고 매수는 지정가 × 수량을 예약, 체결은 `LimitOrderSweeper`가 신선한
시세로, 출처(ADR-085)는 주문 행에서 체결로 이어진다. **PLACED도 발동으로 센다**(쿨다운·하루 한도) — 예약금이 잡힌 주문이 생겼기
때문이다. 이후 사용자가 취소하거나 스위퍼가 취소해도 그 발동권은 돌려주지 않는다(보수적 방향). 호가 단위는 맞추지 않는다(ADR-074 §6).

`submitLimit`에 선택 인자 `idempotencyKey`를 더했다. 시장가와 같은 의미다 — 같은 키면 새 주문·예약 없이 첫 주문의 **현재 상태**
(PENDING이면 fill 없음)를 돌려준다. 사가·스위퍼는 바꾸지 않았다(키는 이미 `orders.idempotency_key` V48 부분 유니크가 받는다).

### 5. 계좌 % — 발동 시점 평가자산, 정수 주 내림

```
평가자산 = 현금 + 미체결 매수 예약금 + 보유 평가액(최신 1분봉 종가)   ← /wallet 총자산과 같은 정의(ADR-091)
수량     = ⌊평가자산 × equity_pct% ÷ 단가⌋    단가 = 지정가 규칙이면 지정가, 시장가면 발동 가격
0주      → SKIPPED("계좌 평가자산 …의 1%(…원)로는 1주(…원)도 살 수 없어 0주") + 발동권 반환(ADR-077)
```

- 평가자산은 `wallet::equity` 네임드 인터페이스의 `PaperEquityQuery`(구현 `WalletService`)로 읽는다 — 같은 숫자를 두 곳에서
  따로 계산하지 않는다. watchrule의 허용 의존성에 `wallet::equity`를 추가했다(wallet은 watchrule을 모른다, 순환 없음).
- 매도 규칙도 같은 식으로 "평가자산의 x%어치"를 판다. 보유가 모자라면 사가가 거부한다(REJECTED, 기존 동작).
- 계산은 발동권을 잡은 **뒤**에 한다 — 평가자산은 발동 시점 값이어야 하고, 건너뛰면 `finally`가 발동권을 돌려준다.
- 1~25%: 한 번의 자동 발동이 계좌의 4분의 1을 넘지 않게 한다. 리스크 게이트는 그대로 통과해야 한다.

### 변경 범위

- 생성 요청에 `targetType/targetGroupId`, `orderType/limitOffsetBps`, `sizeType/equityPct`. 기준(대상·유형·수량 기준)은 PATCH로
  바꾸지 않는다 — 그 기준에 맞는 값(`quantity`·`limitOffsetBps`·`equityPct`)만 고친다.
- 응답에 위 필드 + `targetGroupName`·`targetGroupMissing`, 발동 기록에 `stockId`·`limitPrice`.
- 웹 폼: 대상(종목 하나·관심종목 그룹), 주문 유형(시장가·지정가 + bp), 수량 기준(주·계좌 %). "준비 중" 표시를 걷었다.

## Reasons

- **평가 시점 그룹 구성**은 사용자가 관심종목을 고치는 대로 규칙이 따라간다. 복제 방식의 동기화 문제가 없다.
- **규칙 단위 쿨다운·한도**는 ADR-077의 원자적 claim을 손대지 않고 재사용한다. 그룹이 커져도 규칙 하나가 낼 수 있는 주문 수의
  상한이 사용자가 정한 값 그대로다 — 돈이 덜 움직이는 쪽이 기본값이다. 종목별 쿨다운은 원하면 종목 규칙으로 만든다.
- **트리거로 중지**는 그룹 삭제 경로가 지금(회원 탈퇴 연쇄)과 앞으로(그룹 삭제 API) 몇 개든 한 곳에서 같은 결과를 낸다.
  조용한 연쇄 삭제는 "내 규칙이 사라졌다"를 설명할 수 없다.
- **발동 시점 신선한 시세**를 요구해 오래된 가격으로 지정가·수량이 정해지는 일을 막는다(사가의 시장가 신선도 판정과 같은 기준).
- **평가자산 정의 공유**로 /wallet 총자산과 규칙 수량이 다른 숫자를 쓰지 않는다.

## Consequences

- **watchrule이 watchlist 테이블 이름을 안다**(읽기 전용 JDBC, `WatchRuleTargets`·`WatchRuleQueries`). 그룹·항목 테이블 구조를
  바꾸면 함께 고쳐야 한다. 통합 테스트가 같은 SQL을 실제 Postgres에 실행한다.
- **DB 트리거가 하나 생겼다**(`watch_rules_disable_on_group_delete`). 애플리케이션 코드만 읽으면 보이지 않는 동작이라 V92 주석·이
  ADR·data-model에 적었다.
- 그룹 규칙은 이벤트마다 그룹 소속을 `EXISTS`로 확인한다. 한 종목이 많은 그룹·규칙에 걸리면 컨슈머 지연이 늘 수 있다(ADR-051의 같은
  Revisit 항목).
- 같은 그룹 안 두 종목에 동시에 이벤트가 오면 쿨다운 안에서는 하나만 발동하고 나머지는 SKIPPED(쿨다운)로 남는다 — 의도된 동작이지만
  "왜 B 종목은 안 샀지"가 이력에 보인다.
- PLACED는 체결 전이라 "규칙 경유 손익"(ADR-085, 매도 체결 귀속)에 아직 잡히지 않는다. 미체결 지정가가 나중에 체결돼도 발동 기록은
  PLACED로 남는다 — 체결 여부는 주문·거래 내역에서 본다.
- PLACED 후 사용자가 주문을 취소해도 그날 한도·쿨다운은 이미 쓴 것으로 남는다.
- 평가자산의 보유 평가액은 최신 1분봉 종가(신선도 무관, /wallet과 같은 정의)다. 시세가 끊긴 보유 종목이 있으면 그 시점 평가액이 실제와
  다를 수 있다.
- `OrderSubmitter.submitLimit` 시그니처에 선택 인자가 생겼다(기본 null — 기존 호출부 변경 없음).

## Revisit When

- (규칙, 종목) 단위 쿨다운을 원하는 요구가 생길 때 — 발동권 잠금 단위를 (규칙, 종목) 행으로 옮기는 설계가 필요하다.
- 그룹 삭제 API가 생겨 "삭제 전에 걸린 규칙을 보여 주기" 같은 UX가 필요할 때.
- 지정가 미체결 이후 체결·취소를 발동 기록에 반영해 달라는 요구가 생길 때(주문 상태 이벤트 구독).
- 호가 단위 검증을 모의투자에도 넣을 때 — 지정가 반올림을 호가 단위로 바꾼다.
