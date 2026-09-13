# ADR-043: 원장 조회 커서 페이징과 대사(reconciliation) 스냅샷 — ADR-013 서술 정정

## Status
Accepted

## Context

[scale-out-plan.md](../scale-out-plan.md) §3.6은 이 문제를 "이벤트 소싱을 스냅샷 없이
쓰고 있어 잔고 계산이 O(n)"이라고 적었다. **그 진단은 틀렸다.** ADR을 쓰면서 실제 코드를
따라가 보니 잔고는 replay로 계산되지 않는다.

### 실제 구현

```kotlin
// WalletService.getWalletMap
val cash = accountQueryService.getCashBalance(userId)     // ← paper_accounts.cash 컬럼
...
val recentLedger = ledgerService.getLedger(userId).take(10)   // ← 전체 원장을 읽고 10개만 쓴다
```

```kotlin
// PaperAccountQueryService
fun getCashBalance(userId: Long): Money =
    accountRepo.findByUserId(userId).map { it.cash }.orElse(Money.INITIAL_BALANCE)
```

즉 **현금 잔고의 authoritative source는 `paper_accounts.cash` 컬럼**이다.
`ledger_events.balance_after`는 기록 시점의 계좌 잔고를 그대로 복사해 넣은 비정규화
값이고, 이걸 읽는 곳은 [`ReceiptService`](../../backend/api/src/main/kotlin/com/monticker/api/wallet/application/ReceiptService.kt#L56)
하나뿐이다.

이건 [ADR-013](013-append-only-ledger-wallet.md)이 적은 원칙과 다르다:

> 잔고 계산 원칙: 잔고 = 모든 LedgerEvent를 시간순으로 replay한 합산
> 잔고를 별도 컬럼으로 관리하지 않음 → 이벤트 소싱 패턴

**문서가 서술한 이벤트 소싱은 구현된 적이 없다.** 실제 구조는
"컬럼 잔고 + 병렬 감사 기록(append-only ledger)"이다. 이건 나쁜 구조가 아니다 —
오히려 조회 성능 면에서 낫다. 문제는 **문서와 코드가 다르다는 것**과,
그 구조가 요구하는 안전장치가 없다는 것이다.

### 진짜 결함 2가지

**1) 화면에 10줄 보여주려고 원장 전체를 읽는다.**

[`LedgerEventRepository`](../../backend/api/src/main/kotlin/com/monticker/api/wallet/infrastructure/LedgerEventRepository.kt#L8)의
`findAllByUserIdOrderByCreatedAtDesc(userId)`에는 LIMIT이 없다. 이걸 쓰는 곳:

| 호출부 | 노출 API | 문제 |
|--------|---------|------|
| `WalletService.getWalletMap` → `.take(10)` | `GET /api/wallet` | **지갑 메인 화면.** 전체 행을 JPA 엔티티로 힙에 올린 뒤 10개만 쓴다 |
| `LedgerService.getLedger` | `GET /api/wallet/ledger` | 페이징 없음 — 전체 반환 |

활성 유저의 원장이 10만 건이면 지갑 화면 한 번에 10만 엔티티를 로드한다. 동시 100명이면 OOM이다.
`getLedgerForDate`는 하루로 범위가 제한되지만 행 수 상한은 여전히 없다.

**2) 컬럼 잔고와 원장이 어긋나도 아무도 모른다.**

잔고가 컬럼이고 원장이 병렬 기록이면 **두 값은 드리프트할 수 있다.**
원장 기록은 [`PaperTradeEventListener`](../../backend/api/src/main/kotlin/com/monticker/api/wallet/application/PaperTradeEventListener.kt#L26)/
[`OrderFilledEventListener`](../../backend/api/src/main/kotlin/com/monticker/api/wallet/application/OrderFilledEventListener.kt#L33)가
이벤트 리스너로 하는데, 리스너가 실패하거나 어떤 잔고 변경 경로가 이벤트를 발행하지
않으면 원장에 구멍이 난다. 지금은 그걸 감지할 수단이 전혀 없다.

CLAUDE.md가 명시한 대로 이 프로젝트는 **실제 사용자 자금이 오갈 예정**이다.
"장부와 실제 잔고가 맞는가"를 확인하지 않는 금융 시스템은 성립하지 않는다.

## Decision

### 1. 원장 조회를 커서 페이징으로 바꾼다

`findAllByUserIdOrderByCreatedAtDesc(userId)`를 **삭제**하고 커서 기반으로 대체한다.

```kotlin
interface LedgerEventRepository : JpaRepository<LedgerEvent, Long> {
    // cursor = 이전 페이지 마지막 id. 첫 페이지는 Long.MAX_VALUE
    @Query("""
        SELECT e FROM LedgerEvent e
        WHERE e.userId = :userId AND e.id < :cursor
        ORDER BY e.id DESC
    """)
    fun findPage(userId: Long, cursor: Long, pageable: Pageable): List<LedgerEvent>
}
```

- `GET /api/wallet/ledger?cursor=&limit=` — `limit`은 서버에서 최대 50으로 clamp한다.
- `WalletService.getWalletMap`은 `findPage(userId, MAX, PageRequest.of(0, 10))`로
  **10건만 조회**한다. `.take(10)`을 메모리에서 하지 않는다.
- 정렬 키를 `created_at`에서 **`id`로 바꾼다.** `created_at`은 같은 트랜잭션에서 생성된
  이벤트끼리 동일할 수 있어 커서 페이징의 안정적 정렬 키가 될 수 없다.
  `ledger_events.id`는 BIGSERIAL이라 단조 증가한다.
- 인덱스: 기존 `idx_ledger_events_user_time (user_id, created_at DESC)` 옆에
  `(user_id, id DESC)`를 추가한다.

### 2. 일 단위 잔고 스냅샷 — 목적은 replay 가속이 아니라 대사(reconciliation)다

```sql
CREATE TABLE ledger_snapshots (
    user_id        BIGINT        NOT NULL REFERENCES users(id),
    as_of_date     DATE          NOT NULL,
    ledger_sum     NUMERIC(18,4) NOT NULL,   -- 이 시점까지 원장 amount 합계
    account_cash   NUMERIC(18,4) NOT NULL,   -- 같은 시점 paper_accounts.cash
    last_event_id  BIGINT        NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, as_of_date)
);
```

스냅샷이 없으면 대사할 때마다 유저 원장 전체를 `SUM`해야 한다 — 그게 O(n)이다.
스냅샷이 있으면 **직전 스냅샷 이후 델타만** 더하면 된다.

```
today_ledger_sum = snap.ledger_sum
                 + (SELECT COALESCE(SUM(amount),0) FROM ledger_events
                    WHERE user_id = ? AND id > snap.last_event_id)
```

### 3. 일일 대사 배치 — 불일치를 알람한다

장 마감 후 활성 유저(당일 원장 이벤트가 있는 유저)에 대해:

```
for user in usersWithActivityToday():
    ledgerSum  = snapshotDelta(user)          // §2
    accountCash = paper_accounts.cash
    if abs(ledgerSum - accountCash) > 0.0001:
        log.error + ledger_reconciliation_mismatch_total{userId} 카운터 증가
    upsert ledger_snapshots(user, today, ledgerSum, accountCash, lastEventId)
```

- **불일치를 자동으로 고치지 않는다.** 어느 쪽이 옳은지 기계가 판단할 수 없다.
  알람 → 사람이 조사한다.
- `ledger_reconciliation_mismatch_total > 0`은 **페이지 알람 대상**이다.

### 4. ADR-013의 서술을 실제 구현에 맞게 정정한다

ADR-013 Status 아래에 갱신 노트를 달아, "잔고 = replay"가 구현된 적 없고 실제 구조는
"컬럼 잔고 + append-only 감사 원장 + 일일 대사"임을 기록한다.
ADR-013의 나머지(append-only, 이벤트 타입 체계, 삭제 금지)는 유효하므로
Superseded로 바꾸지 않는다.

## Reasons

- **커서 페이징이 offset 페이징보다 나은 이유**: 원장은 시간 역순으로만 읽힌다.
  `OFFSET n`은 앞의 n행을 스캔해서 버리므로 뒤로 갈수록 느려진다. 커서는 인덱스에서
  바로 진입한다. 새 이벤트가 추가돼도 페이지가 밀리지 않는 이점도 있다.
- **"이벤트 소싱으로 되돌리자"를 고르지 않은 이유**: 잔고를 replay로 계산하면
  주문 처리 경로에서 매번 유저 원장을 합산해야 한다. 현금 예약은
  `UPDATE paper_accounts SET cash = cash - ? WHERE cash >= ?` 단일 원자 문장에 의존하는데
  ([architecture.md](../architecture.md), `CashReservationConcurrencyIntegrationTest`로
  검증된 동시성 보장), 이걸 이벤트 합산으로 바꾸면 그 보장을 다시 설계해야 한다.
  **동작하고 검증된 동시성 설계를 문서와 맞추려고 해체하는 건 잘못된 방향**이다.
  문서를 코드에 맞추는 게 맞다.
- **대사를 지금 넣는 이유**: 나중에 넣으면 "언제부터 어긋났는지" 알 수 없다.
  스냅샷이 매일 쌓여 있어야 드리프트 발생 시점을 좁힐 수 있다. 데이터가 적은 지금이
  가장 싸고, 실제 자금이 들어온 뒤에는 없으면 안 되는 통제다.
- **자동 교정을 하지 않는 이유**: 불일치는 두 가지 원인이 가능하다 — 원장 누락(기록 실패)
  또는 잔고 오염(잘못된 UPDATE). 전자면 원장을 고쳐야 하고 후자면 잔고를 고쳐야 한다.
  방향을 자동으로 정하면 **틀렸을 때 사용자 돈이 사라진다.**

## Consequences

- **`GET /api/wallet/ledger`의 응답 형태가 바뀐다** — 배열에서 `{ items, nextCursor }`로.
  프론트엔드([apps/web](../../apps/web))의 지갑 타임라인이 무한 스크롤로 바뀐다.
  같은 작업 단위에서 처리한다(ADR-033/039의 교훈 — 서버만 바꾸고 클라이언트를 남겨두지 않는다).
- **정렬 키가 `created_at`에서 `id`로 바뀐다.** 두 정렬은 거의 항상 같은 순서를 주지만,
  같은 밀리초에 기록된 이벤트의 표시 순서가 미세하게 달라질 수 있다. 원장 표시 순서에
  의존하는 테스트가 있으면 함께 고친다.
- **`ledger_snapshots` 테이블과 배치 잡이 추가된다.** 활성 유저만 대상이므로 비용은
  유저 수가 아니라 **당일 거래한 유저 수**에 비례한다.
- **대사가 처음 돌면 기존 데이터에서 불일치가 나올 가능성이 높다.** 지금까지 검증된 적이
  없으므로 예상해야 한다. 최초 실행은 **알람 없이 리포트만** 내고, 기존 드리프트를
  조사·정리한 뒤 알람을 켠다.
- **`reservedCash`/`settlementPending`이 하드코딩 0인 문제는 이 ADR의 범위가 아니다.**
  `WalletService.getWalletMap`이 두 값을 `BigDecimal.ZERO`로 반환하고 있어
  "돈의 이동 지도"가 절반만 실데이터다. 대사 대상은 현금 잔고이므로 이 ADR과 독립적이지만,
  같은 화면의 신뢰성 문제이므로 [engineering-backlog.md](../engineering-backlog.md)에
  별도 항목으로 남긴다.
- `trading-service`에도 동일한 `LedgerService`/`LedgerEventRepository` 복사본이 있다
  (MSA 분리 잔재). **두 곳을 함께 고쳐야 한다** — 한쪽만 고치면 모놀리스 모드와 MSA 모드의
  동작이 갈린다.

## Revisit When

- **`ledger_events`가 시간 파티셔닝 대상이 될 때**(scale-out-plan §6.3.3, Phase 2) —
  파티션 키가 `created_at`이 되면 PK를 `(id, created_at)` 복합으로 바꿔야 하고,
  커서 페이징의 정렬 키 선택을 다시 검토해야 한다.
- **대사 불일치가 반복적으로 발생할 때** — 그건 "대사가 부족하다"가 아니라
  "잔고 변경 경로 중 원장을 기록하지 않는 곳이 있다"는 신호다. 그 경로를 찾아
  Outbox([ADR-008](008-outbox-pattern-spring-modulith.md))로 묶는 게 근본 해결이다.
- **실브로커 잔고(BYOK)까지 대사 대상이 될 때** — 증권사 API의 잔고와 monticker의 기록을
  맞추는 건 더 복잡하다(체결 지연, T+2 정산). 별도 ADR이 필요하다.

## 구현 노트 (2026-09-13)

커밋 `ef5fd12` `6fb374a` `68773dd` `ccbac87` `0938819` `f7cf676` `5e113bb` `69595aa`.
api 523/523, 통합 8/8(실제 Postgres), trading-service 20/20, web 40/40. 로컬 스택에서 주문 →
취소 → 매도 → 대사 → 잔고 조작 → 초기화까지 라이브 검증.

### 결정대로 구현한 것

- **§1 커서 페이징** — `findPage(userId, cursor, pageable)`(정렬 키 `id`, 인덱스 `(user_id, id DESC)`),
  `GET /api/wallet/ledger?cursor=&limit=` → `{items, nextCursor}`, limit ≤ 50, **limit+1건을 읽어**
  꽉 찬 마지막 페이지 뒤에 빈 요청이 한 번 더 가지 않게 했다. `WalletService`는 10건만 읽는다.
  `findAllByUserIdOrderByCreatedAtDesc` 삭제. 웹 원장 탭은 `useInfiniteQuery` 무한 스크롤
  ([`WalletLedger.tsx`](../../apps/web/src/components/wallet/WalletLedger.tsx)) — 이전엔 이 탭이
  `/api/wallet/ledger`를 호출조차 안 하고 `recentLedger` 10건만 보여 줬다.
- **§2 스냅샷** — `ledger_snapshots(user_id, as_of_date, ledger_sum, account_cash, reserved_cash,
  last_event_id, mismatch)`. 직전 스냅샷의 `(ledger_sum, last_event_id)`에서 델타만 더한다.
  통합 테스트가 어제 스냅샷을 일부러 오염시켜 델타 경로를 탔음을 증명한다.
- **§3 대사 배치** — `ledgerReconciliationJob` 17:30 KST(페이퍼 16:30·실거래 17:00 정산 후),
  대상은 당일 원장 이벤트가 있는 유저. Job 파라미터 `date`가 인스턴스 식별자라 멀티파드에서
  중복 실행이 거절된다. `POST /api/admin/batch/ledger-reconciliation?date=`로 수동·과거 재대사.
  `ledger_reconciliation_mismatch_total{mode}` + `ledger_reconciliation_checked_total`.
  **자동 교정 없음** — 통합 테스트가 1원 조작 후 잔고·원장이 그대로임을 확인한다.
- **"최초 실행은 리포트만"** — `app.wallet.reconciliation.mode` (`LEDGER_RECON_MODE`). 코드 기본은
  `alert`, 운영 ConfigMap은 `report`로 출발한다. 알람: `LedgerMismatch`(page, `mode="alert"`),
  `LedgerMismatchReported`(ticket, `mode="report"`), `LedgerReconciliationDidNotRun`(ticket, 26h 침묵).
  `report` → `alert` 전환은 [human-action-items §3](../human-action-items.md).

### 결정에서 벗어난 것 — 불변식을 정확히 하려면 필요했다

이 ADR의 §3은 `ledgerSum ≈ paper_accounts.cash`를 비교한다고 적었다. 코드를 따라가 보니
**그 등식은 성립한 적이 없다.** 실제 불변식은:

```
paper_accounts.cash + reserved  =  10,000,000  +  Σ ledger.amount[현금 영향 타입]
```

- **초기 지급 1,000만 원은 원장에 없다** — `PaperAccount` 생성 시 DEPOSIT을 쓰지 않는다. 상수로 더한다.
- **예약금(`reserved`)** — `OrderSagaOrchestrator.reserveCash`는 제출 시점에 `limit_price × 수량`을
  cash에서 빼지만 원장에는 아무것도 쓰지 않는다(예약은 실현된 이동이 아니다). 미체결 BUY 주문의
  `limit_price × (quantity − filled_qty)`를 잔고 쪽에 되돌려 더한다. 같은 SQL(`RESERVED_CASH_SQL`)로
  지갑 화면의 `reservedCash`도 채웠다 — 백로그의 "하드코딩 0" 항목 절반이 이 김에 해결됐다
  (`settlementPending`은 여전히 0, 별도).
- **현금 영향 타입만 합산** — `SUBSCRIPTION_PAYMENT`(PG)·`CREATOR_*`(정산 계좌)·`BROKERAGE_SETTLEMENT`
  (증권사 계좌)는 모의투자 현금과 무관한데 같은 테이블에 있다. `CASH_RESERVED/UNRESERVED`는 예약금
  이동이라 `reserved` 항에서 이미 상쇄된다. 목록은 `LedgerReconciliationService.CASH_EVENT_TYPES`.

그리고 불변식을 세우자 **원장 자체가 거짓말하는 경로 두 개**가 드러났다(`6fb374a`):

- **주문 취소 환불이 `DEPOSIT`이었다.** 예약은 원장에 없었는데 반환만 입금으로 적히니 원장 합이
  잔고보다 환불액만큼 커진다. `CASH_UNRESERVED`로 바꿨다 — 타임라인에는 남고 합계에서는 빠진다.
- **계좌 초기화(`PaperTradingService.reset`)가 잔고를 1,000만으로 되돌리며 원장에 아무것도 안 썼다.**
  초기화한 유저는 영구히 불일치였을 것이다. paper가 `PaperAccountResetEvent`를 내고 wallet이
  `(초기 − 직전 잔고)`를 `DEPOSIT`/`WITHDRAWAL`("모의투자 계좌 초기화")로 기록한다.

### 라이브 검증에서 발견한 결함 — ADR과 무관하지만 원장의 존재 자체에 관한 것

- **원장 INSERT가 전부 실패하고 있었다** (`5e113bb`). `LedgerEvent.metadataJson`이
  `columnDefinition = "jsonb"`만 있고 `@JdbcTypeCode(SqlTypes.JSON)`이 없어 Hibernate 6가 varchar로
  바인딩하고 Postgres가 거부했다 — **null이어도**. 첫 라이브 실행에서 주문 4건이 체결·취소됐는데
  `ledger_events`는 0행, `event_publication`에 미완료 5건. `BehaviorScore`도 같은 결함.
  `RebalanceTarget`이 이미 같은 수정을 같은 설명과 함께 갖고 있었다 — 즉 **이 패턴이 한 번 발견되고도
  같은 모듈 안의 다른 엔티티는 점검되지 않았다.** mock 단위테스트는 절대 못 본다;
  `LedgerEventPersistenceIntegrationTest`가 실제 Hibernate 매핑으로 실제 스키마에 쓴다
  (애노테이션을 지우면 실패함을 확인). 나머지 미수정 jsonb 컬럼은 [engineering-backlog §9](../engineering-backlog.md).
- **`ledger_events.paper_trade_id → paper_trades(id)` FK가 매칭 엔진 경로를 막고 있었다.**
  `OrderFilledEventListener`는 이 컬럼에 `fills.id`를 넣는다. V43에서 FK를 풀고 컬럼 코멘트로
  이중 의미를 기록했다. 원장은 append-only 감사 기록이라 원 거래 행(`reset`이 지우는
  `paper_trades`)이 사라져도 살아남아야 하므로 FK 부재가 맞다.
- **`ReceiptService.getReceipt`가 `ledgerRepo.findAll()`** — 영수증 1장에 **전 유저** 원장을 힙에
  올려 filter했다. ADR이 적은 "지갑 화면 10줄에 전체 로드"보다 나쁜 경로였다.
  `findTopByPaperTradeIdOrderByIdDesc` + 부분 인덱스.

### 알고 남겨 둔 것

- **초기화 + 미체결 주문 = 돈이 생긴다.** `reset`은 cash를 1,000만으로 돌리지만 `orders`는 건드리지
  않는다. 라이브 검증에서 예약금 128,182원을 둔 채 초기화하니 불변식은 성립했지만(원장이 정직하게
  기록하므로) 그 주문이 나중에 취소되면 유저는 1,000만 + 128,182원을 갖는다. 대사가 잡을 수 없는
  종류의 오류다 — 대사는 "장부와 잔고가 맞는가"를 보지 "장부가 옳은가"를 보지 않는다.
  `reset`이 미체결 주문을 먼저 취소하거나 예약금을 초기화 금액에서 빼야 한다. matching 모듈의
  변경이라 [engineering-backlog §9](../engineering-backlog.md).
- **스냅샷 델타의 경계** — `id > last_event_id`로 델타를 잡으므로, 직전 대사 시점에 아직 커밋되지
  않은(더 작은 id의) 트랜잭션이 있었다면 그 이벤트는 영영 합산되지 않는다. 배치가 장 마감 후
  거래가 없는 시각에 돌고 원장 트랜잭션은 밀리초 단위라 실질적 위험은 낮지만, 불일치가 나면
  이 가능성도 후보에 넣어야 한다. 의심되면 해당 유저의 스냅샷을 지우고 재대사하면 전체 합산으로 돌아간다.

