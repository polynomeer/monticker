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
