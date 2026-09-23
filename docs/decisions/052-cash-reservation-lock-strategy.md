# ADR-052: 현금 예약은 원자적 조건부 UPDATE로 한다 (락 전략 실측 비교)

## Status
Accepted

## Context

모의투자 주문의 현금 예약(`OrderSagaOrchestrator.reserveCash`)은 한 계좌 행에 동시 쓰기가 몰리는 지점이다.
[ADR-011](011-order-saga-orchestration.md) 초기 구현은 `SELECT cash` → `require(cash >= amount)` →
`UPDATE cash - amount` 3단계였고, 동시에 들어온 두 요청이 같은 SELECT 결과를 보고 각자 "충분" 판정을 내려
둘 다 차감할 수 있었다. [launch-plan Phase 0](../launch-plan.md)에서 이를 P0로 분류하고
`UPDATE … WHERE cash >= ?` 한 문장으로 바꿨다.

그때는 **정확성만 보고 고쳤고 대안과 비교하지 않았다.** 이제 watch rule([ADR-051](051-event-triggered-paper-orders.md))이
사용자 조작 없이도 주문을 내기 시작하면서 이 지점의 경합이 늘 수 있어, 선택을 근거와 함께 남겨둘 필요가 생겼다.

### 후보

| | 방식 | DB 왕복 | 판단 주체 |
|---|---|---|---|
| **ATOMIC** | `UPDATE … SET cash = cash - ? WHERE user_id = ? AND cash >= ?` | 1 | DB(WHERE 절) |
| **PESSIMISTIC** | `SELECT … FOR UPDATE` → 검사 → `UPDATE` (한 트랜잭션) | 2 + 트랜잭션 | 애플리케이션 |
| **OPTIMISTIC** | `SELECT` → 검사 → `UPDATE … WHERE cash = ?`(CAS) → 실패 시 재시도 | 2+ (재시도마다 증가) | 애플리케이션 |

## Decision

**ATOMIC** — 확인과 차감을 조건부 `UPDATE` 한 문장으로 합친다. 현재 구현을 유지하되, 이제 근거가 있다.

### 측정 방법

`CashReservationStrategyBenchmark`(integrationTest)가 세 구현에 **같은 부하**를 건다.

```
환경    Testcontainers timescale/timescaledb:latest-pg16, 로컬 Docker (macOS, Apple Silicon)
부하    20 스레드 × 25 시도 = 500 ops, 단일 계좌 행에 집중
        계좌 잔고는 정확히 200회만 통과 가능 — 300회는 잔고 부족으로 실패해야 한다
커넥션  HikariCP, maximumPoolSize=10 (api 기본값)
반복    3 라운드 평균, 워밍업 1라운드는 측정에서 제외
확인    같은 벤치마크를 3회 독립 실행해 순위가 재현되는지 봤다
```

**첫 측정은 틀렸고, 그것을 발견해 다시 쟀다.** 처음에는 통합 테스트 베이스의 `DriverManagerDataSource`를
그대로 썼는데, 이 구현은 커넥션을 풀링하지 않고 **매 연산마다 TCP 연결을 새로 맺는다**. 그 비용이
전략 간 차이를 완전히 덮어서 ATOMIC 319 ops/s, PESSIMISTIC 322 ops/s로 구분이 되지 않았다(p50 ≈ 50ms).
운영은 HikariCP를 쓰므로 측정도 그쪽에 맞춰 다시 했다. 아래 수치는 재측정분이다.

### 결과 (3회 독립 실행)

| 전략 | ops/s (run 1 / 2 / 3) | elapsed | p50 | p95 | p99 | CAS 재시도 |
|---|---|---|---|---|---|---|
| **ATOMIC** | 7710 / 6755 / 8107 | 63~78ms | ~1.0ms | ~6ms | ~31ms | 0 |
| **PESSIMISTIC** | 1076 / 795 / 1255 | 402~629ms | ~12.9ms | ~58ms | ~99ms | 0 |
| **OPTIMISTIC** | 1642 / 1141 / 1685 | 297~441ms | ~1.9ms | ~69ms | ~141ms | 3126~3234 |

정확성은 세 전략 **모두 통과**했다. 매 실행마다 (1) 잔고가 음수가 되지 않고, (2) 성공 횟수가 허용치를
넘지 않으며, (3) `잔고 = 초기 - 성공횟수 × 단가`가 정확히 성립하는 것을 단언한다.
ATOMIC·PESSIMISTIC은 성공 횟수가 정확히 200이었고, OPTIMISTIC은 재시도를 소진해 통과 가능한 시도를
일부 놓칠 수 있어 상한만 검사한다.

## Reasons

- **정확성만으로는 셋을 가를 수 없다.** 세 전략 모두 이중 차감을 막는다. 그래서 비용이 판단 기준이 된다.
- **ATOMIC이 처리량에서 PESSIMISTIC보다 약 7배 빨랐다**(평균 7524 vs 1042 ops/s). 왕복이 1회이고
  애플리케이션이 락을 들고 있는 구간이 없기 때문이다. PESSIMISTIC은 `FOR UPDATE`부터 커밋까지
  네트워크 왕복 2회가 락 안에 들어간다 — 경합이 클수록 이 구간이 직렬화된다.
- **OPTIMISTIC은 500회 시도에 3,100회 이상 재시도했다.** 단일 행에 20스레드가 몰리는 이 부하에서는
  CAS가 거의 매번 밀린다. p50은 ATOMIC에 가깝지만(1.9ms) p99가 141ms로 벌어지는 것이 그 결과다.
  재시도 상한을 두면 통과 가능한 요청을 놓치고, 두지 않으면 꼬리 지연이 무제한이 된다.
- **코드가 가장 단순하다.** 판단이 SQL 한 줄 안에 있어서 "검사와 차감 사이"라는 구간 자체가 없다.
  리뷰어가 레이스를 추론할 필요가 없는 것이 실수를 막는다.

## Consequences

- **거부 사유를 구분할 수 없다.** `UPDATE`가 0행이면 "잔고 부족"인지 "계좌 없음"인지 알 수 없다.
  지금은 호출 전에 `ensureAccountExists`로 계좌를 보장해 이 모호함을 없앴다 — 왕복이 하나 늘었지만
  이 경로는 주문당 1회라 측정된 차이를 뒤집지 않는다.
- **여러 필드를 함께 바꿔야 하면 이 방식이 안 맞는다.** 조건부 UPDATE는 한 행의 한 조건에만 자연스럽다.
  예약과 동시에 다른 테이블을 조건부로 바꿔야 하는 요구가 생기면 PESSIMISTIC으로 돌아가야 한다.
- **측정은 단일 행 최악 경합이다.** 실제로는 사용자마다 다른 행이라 경합이 훨씬 낮고, 세 전략의 차이도
  그만큼 줄어든다. 이 수치는 **전략 간 상대 비교**로만 읽어야 하며 SLO나 용량 산정에 쓰면 안 된다 —
  로컬 Docker 한 대에서 잰 값이고, 운영은 네트워크·디스크·커넥션 풀 경합이 다르다.
- **벤치마크가 통합 테스트 스위트에 있다.** 3라운드 × 3전략이 매 실행마다 돈다(약 2초). 정확성 단언이
  회귀 방지에 쓰이므로 의도한 비용이지만, 더 무거워지면 태그로 분리해야 한다.

## Revisit When

- 한 계좌에 동시 주문이 실제로 몰리는 사용 패턴이 생겼을 때 — 예: watch rule이 한 종목의 이벤트로
  같은 사용자의 룰 여러 개를 동시에 발동시키는 경우.
- 예약이 `paper_accounts` 외의 테이블을 함께 조건부로 바꿔야 할 때.
- [scale-out-plan](../scale-out-plan.md)에서 계좌를 샤딩하거나 잔고를 캐시로 올릴 때 — 그때는 락 위치
  자체가 DB 밖으로 나간다.
