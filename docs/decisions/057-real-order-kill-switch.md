# ADR-057: 실거래 주문 킬 스위치 — Postgres 플래그, 전역·증권사·사용자 범위, 주문 준비 트랜잭션에서 판정

## Status
Accepted

## Context

[2026-10 설계 리뷰](../design-review-2026-10.md) 사고실험 6 — "실주문 전체를 지금 즉시 멈출 수 있는가" — 의 답이 **아니오**였다.
있는 통제 수단은 둘뿐이다:

- `app.brokerage.mock.enabled` — **부팅 시** 빈을 바꾸는 설정. 바꾸려면 재배포해야 하고, 바꾸면 Mock 증권사로 주문이 "성공"한다
  (사용자는 체결된 줄 안다).
- 증권사별 서킷브레이커 — 증권사가 **실패할 때만** 열린다. 증권사는 정상인데 우리가 멈춰야 하는 경우(잘못된 배포가 주문을
  잘못 만든다, 시세가 오염됐다, 특정 계정의 자격증명이 유출됐다, 규제 당국 요청)에는 아무 역할도 못 한다.

실거래가 공개되면 이 스위치는 **사고 대응의 첫 단계**다. 원인을 찾기 전에 피해가 늘어나는 것부터 막아야 한다.

설계에서 고른 것들과 대안:

| 결정 | 고른 것 | 대안과 기각 이유 |
|---|---|---|
| 저장소 | **Postgres 테이블** | Redis 플래그 — 이미 하드 의존성인데 fail 정책이 없다([resilience-plan A1](../resilience-plan.md)). 돈을 막는 스위치가 "Redis가 죽으면 열린다/모든 주문이 막힌다" 중 하나를 고르게 된다. Postgres는 주문 준비 트랜잭션(ADR-056 tx1)이 이미 쓰고 있어 **새 의존성이 0**이다. 설정 파일/환경변수 — 재배포 필요 |
| 캐시 | **없음** | 주문마다 인덱스 조회 한 번(부분 인덱스, 행 수 ≈ 활성 스위치 수). 캐시를 두면 "켰는데 N초 동안 주문이 나간다"가 생긴다 |
| 판정 위치 | **`BrokerageService.prepareOrder`(tx1) 한 곳** | 수동·조건부·리밸런싱 주문이 모두 이곳을 지난다. 컨트롤러 필터 — 내부 경로(조건부 주문)를 놓친다 |
| 범위 | **전역 · 증권사(KIS/TOSS) · 사용자**, 전부 관리자 전용 | 사용자 셀프 잠금 — 사용자 결정(2026-10-05)으로 이번 범위 밖 |
| 조건부 주문 | **평가 일시정지 — ACTIVE 유지, 해제 후 자동 재개** | SUSPENDED로 바꾸고 재무장 요구 — 사용자 결정(2026-10-05)으로 기각. 재무장을 잊으면 보호가 사라진다 |

## Decision

### 1. `trading_halts` — 켜고 끈 이력이 그대로 남는 테이블

```sql
CREATE TABLE trading_halts (
    id          BIGSERIAL PRIMARY KEY,
    scope       VARCHAR(10) NOT NULL CHECK (scope IN ('GLOBAL','PROVIDER','USER')),
    target      VARCHAR(50),             -- PROVIDER: 'KIS'|'TOSS', USER: user id, GLOBAL: NULL
    reason      TEXT NOT NULL,
    halted_by   BIGINT REFERENCES users(id),   -- NULL = SQL로 직접(앱이 죽었을 때의 비상 경로)
    halted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    lifted_by   BIGINT REFERENCES users(id),
    lifted_at   TIMESTAMPTZ,
    lift_reason TEXT,
    CHECK ((scope = 'GLOBAL') = (target IS NULL))
);
-- 같은 범위·대상에 활성 스위치는 하나
CREATE UNIQUE INDEX ux_trading_halts_active ON trading_halts (scope, COALESCE(target, '')) WHERE lifted_at IS NULL;
```

행은 지우지 않는다. 해제는 `lifted_*`를 한 번 채우는 것(`UPDATE … WHERE lifted_at IS NULL`)이다. 그래서 테이블 자체가
"누가 언제 왜 켜고 껐는가"의 감사 기록이다 — 기존 `@Audited`는 구조화 로그만 남긴다.

**비상 경로**: 앱이 죽었거나 관리자 인증이 안 될 때도 DB에 직접
`INSERT INTO trading_halts (scope, reason) VALUES ('GLOBAL', '…')` 하면 **다음 주문부터 즉시** 막힌다(캐시가 없다).
→ [runbooks/trading-halt.md](../runbooks/trading-halt.md)

### 2. 판정 — 주문 준비 트랜잭션 안에서

`prepareOrder`(ADR-056 tx1)가 계좌를 읽은 직후, 리스크 게이트·의도 기록보다 **먼저**:

```sql
SELECT … FROM trading_halts
WHERE lifted_at IS NULL
  AND (scope = 'GLOBAL' OR (scope = 'PROVIDER' AND target = :provider) OR (scope = 'USER' AND target = :userId))
LIMIT 1
```

걸리면 `TradingHaltedException` → **503**. 의도 행을 만들지 않으므로 증권사 호출도 없다.

- **효력 시점**: 스위치 커밋 이후 tx1에 들어오는 주문부터. 이미 tx1을 통과한 주문(증권사 호출 중, 길어야 수 초)은 나간다.
  "켜는 순간 진행 중인 것까지 회수"는 불가능하다 — 증권사로 이미 나간 주문은 취소로만 되돌린다.
- **사용자에게 보이는 사유**: 전역·증권사 스위치는 운영자가 쓴 사유를 그대로 보여준다(점검 공지 등). **사용자 스위치는 사유를
  숨기고** "계정의 실거래 주문이 제한되었습니다. 고객센터로 문의해주세요"만 보여준다 — 유출 의심 등 내부 판단을 노출하지 않는다.

### 3. 멈추지 않는 것

| 동작 | 스위치 중 | 이유 |
|---|---|---|
| 주문 **취소** | 허용 | 위험을 줄이는 방향이다. 사고 중에 미체결 주문을 거둬들일 수 있어야 한다 |
| 결과 불명 **대조**(ADR-056) | 허용 | 조회만 한다. 멈추면 상태를 모르는 주문이 늘어난다 |
| 잔고·주문 **조회** | 허용 | |
| 모의투자 | 무관 | 실제 돈이 아니다 |

### 4. 조건부 주문 — 발동하지 않고 ACTIVE로 남는다

평가기가 조건 충족 행을 **클레임하기 전에** 그 주문의 증권사·사용자에 걸린 스위치를 본다. 걸리면 건너뛴다(ACTIVE 유지,
`conditional_order_halted_total` 증가). 해제되면 다음 실시세 틱부터 평소처럼 평가된다 — **해제 직후 첫 틱이 조건을 만족하면 그
가격에 발동한다**(스탑로스의 원래 동작과 같다: 갭 아래로 열리면 갭 가격에 나간다).

평가기 확인과 tx1 확인 사이에 스위치가 켜지면 tx1이 `TradingHaltedException`을 던진다. 평가기는 이 예외를 받으면 행을
`TRIGGERED → ACTIVE`로 되돌린다 — 스위치 때문에 조건부 주문이 `FAILED`로 소모되는 경로는 없다.

### 5. 리밸런싱

`execute()` 시작 시 스위치를 확인하고 걸리면 실행 기록을 만들지 않고 503. leg마다 실패가 쌓이는 것을 막는다.

### 6. 관리·노출

- `POST /api/admin/trading-halts` `{scope, target?, reason}` · `POST /api/admin/trading-halts/{id}/lift` `{reason}` ·
  `GET /api/admin/trading-halts?active=true` — `@PreAuthorize("hasRole('ADMIN')")` + `@Audited`. 대상 검증: PROVIDER는
  `KIS|TOSS`, USER는 존재하는 사용자. 같은 범위에 이미 활성 스위치가 있으면 409.
- `GET /api/brokerage/trading-status` — 로그인 사용자 기준 `{halted, scope, message}`. 주문 화면 배너용.
- 메트릭: `trading_halt_active{scope}` 게이지, `brokerage_order_blocked_by_halt_total{scope}`, `conditional_order_halted_total`.
- 알림: `TradingHaltActive`(Ticket) — 전역·증권사 스위치가 30분 넘게 켜져 있다. **잊힌 스위치는 조용한 장애다.**

## Reasons

- 새 의존성 없이, 이미 지나가는 트랜잭션 안에서 한 번 판정한다. 캐시가 없어 켜는 즉시 효력이 있다.
- 앱이 죽어도 SQL 한 줄로 켤 수 있다 — 킬 스위치는 가장 나쁜 상황에서 쓰인다.
- 테이블이 곧 감사 기록이다.
- 조건부 주문이 스위치 때문에 소모되지 않는다 — 사고가 사용자의 보호 장치를 지우지 않는다.

## Consequences

- 실주문마다 조회 한 번이 늘어난다(부분 인덱스, 수 μs~ms). 주문 경로 SLO(p99 < 500ms)에 비해 무시할 수준이지만 측정하지는 않았다.
- **Postgres가 죽으면 스위치도 확인할 수 없다** — 그때는 tx1 자체가 실패하므로 주문도 나가지 않는다(fail-closed와 같은 결과).
- **해제 직후 조건부 주문이 몰려 발동할 수 있다.** 긴 전역 정지 뒤에 시장이 크게 움직였다면 많은 스탑로스가 첫 틱에 함께
  나간다. 해제 전에 활성 조건부 주문 수를 보고 판단하라(런북).
- 진행 중인 주문(tx1 통과 후 수 초)은 막지 못한다.
- 사용자 스위치는 해당 사용자의 **모든 증권사 계좌**를 막는다(사용자당 활성 계좌는 하나다 — ADR-026).
- 자동으로 켜지지 않는다. 사람이 켠다.

## Revisit When

- 자동 정지가 필요해질 때 — 예: 결과 불명 주문이 짧은 시간에 N건 이상(ADR-056 `brokerage_order_unresolved` 급증), 리스크 게이트
  거부율 급등, 시세 출처 이상(ADR-055 게이트 무시 비율 급변). 오탐이 곧 전체 실거래 중단이라 임계값을 실측한 뒤 넣는다.
- 사용자 셀프 잠금(패닉 버튼)이 요구될 때 — 해제 재확인 UX와 함께.
- 종목 단위 정지(거래정지 종목·오염된 시세 종목)가 필요할 때 — `scope = 'SYMBOL'` 추가.
