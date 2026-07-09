# EDA (Event-Driven Architecture) 적용 가이드

monticker에 적용된 이벤트 기반 아키텍처 패턴을 기술한다.  
각 적용 사례별로 **도입 배경 → 설계 결정 → 구현 세부사항 → 트레이드오프**를 다룬다.

---

## 목차

1. [전체 이벤트 맵](#1-전체-이벤트-맵)
2. [주문 체결 이벤트 (OrderFilledEvent)](#2-주문-체결-이벤트-orderfilledevent)
3. [알림 규칙 실시간 평가 (TickProcessedEvent)](#3-알림-규칙-실시간-평가-tickprocessedevent)
4. [행동 등급 변경 알림 (GradeChangedEvent)](#4-행동-등급-변경-알림-gradechangedevent)
5. [공통 설계 원칙](#5-공통-설계-원칙)
6. [검토 결과 — 적용하지 않은 곳](#6-검토-결과--적용하지-않은-곳)

---

## 1. 전체 이벤트 맵

```
[API 서버]
  MatchingService
    └─ submitOrder() / cancelOrder()
         ├─ publishEvent(OrderFilledEvent)   ──► OrderFilledEventListener   (지갑 원장 기록)
         └─ publishEvent(OrderCancelledEvent) ─► OrderFilledStrategyListener (전략 로그)

  BehaviorScoreService
    └─ calculateAndSave()
         └─ publishEvent(GradeChangedEvent) ──► GradeChangedEventListener   (등급 변경 푸시)

[Worker 서버]
  MarketDataCollector (internal 모드)
    └─ collect() per tick
         └─ publishEvent(TickProcessedEvent) ─► AlertEvaluator              (알림 규칙 평가)

  TickPipelineConfig (kafka 모드)
    └─ alertEvaluateFlow: tickChannel subscriber
         └─ publishEvent(TickProcessedEvent) ─► AlertEvaluator              (알림 규칙 평가)
```

모든 이벤트는 **Spring ApplicationEventPublisher** 기반 인-프로세스 이벤트다.  
서비스 간 경계를 넘는 메시지(Kafka `market.ticks`, `market.events`)와는 별개다.

---

## 2. 주문 체결 이벤트 (OrderFilledEvent)

### 2-1. 도입 배경

주문 체결(`MatchingService.submitOrder`) 후 두 가지 부가 작업이 필요하다:
- **지갑 모듈**: 체결금액을 원장(LedgerEvent)에 기록
- **퀀트 모듈**: 전략 체결 로그 업데이트

체결 로직 안에 두 모듈의 코드를 직접 호출하면 matching 모듈이 wallet·quant 모듈에 직접 의존하게 된다.  
Spring Modulith `modules.verify()`가 이 방향 의존을 허용하지 않는다.

### 2-2. 설계

```
matching ──publish──► ApplicationEventPublisher
                              │
              ┌───────────────┼───────────────────┐
              ▼                                    ▼
  wallet.OrderFilledEventListener        quant.OrderFilledStrategyListener
  @ApplicationModuleListener             @ApplicationModuleListener
  @TransactionalEventListener(AFTER_COMMIT)
```

`@ApplicationModuleListener`는 Spring Modulith가 제공하는 조합 어노테이션으로,  
`@TransactionalEventListener(phase = AFTER_COMMIT)` + Modulith 경계 검증을 동시에 적용한다.

**AFTER_COMMIT을 선택한 이유**: 체결 트랜잭션이 롤백될 경우 원장에 기록하면 안 된다.  
커밋 후에만 리스너가 실행되므로 부정합이 발생하지 않는다.

### 2-3. 이벤트 스키마

```kotlin
// matching/events/OrderFilledEvent.kt
@Externalized  // Spring Modulith: event_publication 테이블에 발행 이력 기록
data class OrderFilledEvent(
    val orderId: Long,
    val userId: Long,
    val stockId: Long,
    val side: String,           // "BUY" | "SELL"
    val filledQty: Int,
    val filledPrice: BigDecimal,
    val filledAmount: BigDecimal,
    val remainingQty: Int,
    val refundAmount: BigDecimal,
)
```

`@Externalized`를 붙이면 Spring Modulith가 `event_publication` 테이블에 발행 이력을 남긴다.  
애플리케이션 재시작 시 미완료 이벤트를 재처리할 수 있다 (at-least-once).

### 2-4. 리스너 구현

```kotlin
// wallet/application/OrderFilledEventListener.kt
@Component
class OrderFilledEventListener(private val jdbc: JdbcTemplate) {

    @ApplicationModuleListener
    fun onOrderFilled(event: OrderFilledEvent) {
        val ledgerType = if (event.side == "BUY") "FILL" else "SETTLEMENT"
        jdbc.update(
            "INSERT INTO ledger_events (user_id, ...) VALUES (?, ...)",
            event.userId, ledgerType, event.filledAmount, ...
        )
    }

    @ApplicationModuleListener
    fun onOrderCancelled(event: OrderCancelledEvent) {
        if (event.refundAmount > BigDecimal.ZERO) {
            jdbc.update("INSERT INTO ledger_events ...", event.userId, "DEPOSIT", ...)
        }
    }
}
```

### 2-5. event_publication 테이블

`@Externalized` 이벤트는 발행 전 DB에 기록된다. 리스너가 성공하면 `completion_date`가 채워진다.

```sql
-- V18__create_spring_modulith_event_publication.sql
CREATE TABLE event_publication (
    id               UUID        PRIMARY KEY,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ           -- NULL이면 미완료
);
```

재시작 후 `completion_date IS NULL` 레코드를 Spring Modulith가 자동 재처리한다.

---

## 3. 알림 규칙 실시간 평가 (TickProcessedEvent)

### 3-1. 도입 배경

**Before**: `AlertEvaluator`가 `@Scheduled(fixedDelay=5000)`으로 5초마다 전체 `alert_rules`를 로드한 뒤, 각 종목의 현재가를 DB에서 재조회해 평가했다.

```
매 5초:
  SELECT * FROM alert_rules WHERE is_active = true  →  N개 룰
  N번 × SELECT close FROM candles_1m WHERE stock_id = ?
  → 평가 → 조건 충족 시 푸시
```

문제점:
- **최대 5초 지연**: 조건 충족 직후 발송까지 최대 5초 대기
- **비효율적 쿼리**: 가격 변동 없는 종목도 매 5초 재조회
- **self-injection 해킹**: `@Async` 프록시 우회를 위해 `@Autowired @Lazy var self: AlertEvaluator` 필요

### 3-2. 설계

틱이 처리될 때마다 Spring Application Event를 발행한다.  
`AlertEvaluator`는 폴링 대신 이벤트를 수신해 **해당 종목의 룰만** 평가한다.

```
[internal 모드]                    [kafka 모드]
MarketDataCollector.collect()      TickPipelineConfig.alertEvaluateFlow
  per tick                           tickChannel subscriber
      │                                     │
      └──────── publishEvent(TickProcessedEvent(stockId, price)) ──────┘
                                            │
                              AlertEvaluator.onTickProcessed()
                              @EventListener @Async("alertDispatchExecutor")
                                            │
                              SELECT ... WHERE stock_id = ?  (해당 종목만)
                              가격 재조회 없음 (틱 price 직접 사용)
                                            │
                              조건 충족 → dispatchAlert()
```

두 ingestion 경로(internal Mock / Kafka)가 동일한 `TickProcessedEvent`를 발행하므로  
`AlertEvaluator`는 ingestion 경로를 알 필요가 없다.

### 3-3. 이벤트 스키마

```kotlin
// worker/alert/TickProcessedEvent.kt
data class TickProcessedEvent(
    val stockId: Long,
    val price: BigDecimal,
)
```

### 3-4. 리스너 구현

```kotlin
// worker/alert/AlertEvaluator.kt
@Component
class AlertEvaluator(private val jdbc: JdbcTemplate, private val pushSender: ExpoPushSender) {

    @EventListener
    @Async("alertDispatchExecutor")  // 틱 처리 스레드 블로킹 방지
    fun onTickProcessed(event: TickProcessedEvent) {
        val rules = fetchRulesForStock(event.stockId)  // WHERE stock_id = ?
        for (rule in rules) evaluateRule(rule, event.price)
    }

    private fun evaluateRule(rule: AlertRuleRow, currentPrice: BigDecimal) {
        val triggered = when (rule.ruleType) {
            "PRICE_ABOVE" -> currentPrice > threshold(rule)
            "PRICE_BELOW" -> currentPrice < threshold(rule)
            else -> false
        }
        if (triggered) dispatchAlert(rule, currentPrice)
    }
}
```

`@Async`가 `@EventListener`에 붙으면 이벤트 핸들러가 별도 스레드에서 실행된다.  
틱 처리 루프(`collect()` 또는 Spring Integration 파이프라인)는 평가 완료를 기다리지 않는다.

### 3-5. 개선 수치

| 항목 | Before | After |
|---|---|---|
| 알림 지연 | 최대 5초 | 틱 단위 (~1초) |
| 평가 주기 | 고정 5초 | 가격 변동 시에만 |
| DB 쿼리 | `1 + N(룰 수)` / 5초 | `1(해당 종목)` / 틱 |
| 가격 재조회 | 매번 DB 재조회 | 틱 price 직접 사용 |
| 코드 복잡도 | self-injection 필요 | 제거 |

---

## 4. 행동 등급 변경 알림 (GradeChangedEvent)

### 4-1. 도입 배경

`BehaviorScoreService.calculateAndSave()`는 투자 행동 점수를 계산하고 DB에 저장한다.  
점수가 등급 경계를 넘었을 때 사용자에게 알리는 기능이 없었다.

등급 알림을 `calculateAndSave()` 안에 직접 구현하면:
- 점수 계산 로직과 푸시 발송 인프라가 같은 메서드에 혼재
- Batch가 전 유저를 병렬 계산하는 중 하나의 푸시 실패가 계산 결과에 영향을 줄 위험

### 4-2. 등급 체계

```kotlin
// wallet/domain/BehaviorGrade.kt
enum class BehaviorGrade(val label: String) {
    POOR("위험"),       // score < 40
    FAIR("보통"),       // 40 ≤ score < 60
    GOOD("양호"),       // 60 ≤ score < 80
    GREAT("우수"),      // 80 ≤ score < 90
    EXCELLENT("탁월");  // score ≥ 90

    companion object {
        fun fromScore(score: Int): BehaviorGrade = when {
            score >= 90 -> EXCELLENT
            score >= 80 -> GREAT
            score >= 60 -> GOOD
            score >= 40 -> FAIR
            else        -> POOR
        }
    }
}
```

### 4-3. 설계

```
BehaviorScoreService.calculateAndSave()
  │
  ├─ 전일 등급 조회 (scoreRepo.findByUserIdAndScoreDate(date-1))
  ├─ 점수 계산
  ├─ BehaviorScore 저장 (grade 컬럼 포함)
  │
  └─ previousGrade ≠ newGrade ?
       └─ publishEvent(GradeChangedEvent(...))
                │
                ▼  @TransactionalEventListener(AFTER_COMMIT)
                   @Async("behaviorScoreExecutor")
       GradeChangedEventListener.onGradeChanged()
                │
                ├─ device_tokens 조회 (WHERE user_id = ?)
                ├─ 메시지 구성 (↑상승 / ↓하락 구분)
                └─ Expo Push API 호출
```

### 4-4. 이벤트 스키마

```kotlin
// wallet/events/GradeChangedEvent.kt
data class GradeChangedEvent(
    val userId: Long,
    val scoreDate: LocalDate,
    val previousGrade: BehaviorGrade?,  // null = 최초 계산
    val newGrade: BehaviorGrade,
    val behaviorScore: Int,
)
```

### 4-5. 리스너 구현

```kotlin
// wallet/application/GradeChangedEventListener.kt
@Component
class GradeChangedEventListener(private val jdbc: JdbcTemplate, ...) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("behaviorScoreExecutor")
    fun onGradeChanged(event: GradeChangedEvent) {
        val tokens = jdbc.queryForList(
            "SELECT token FROM device_tokens WHERE user_id = ? AND is_active = true",
            String::class.java, event.userId,
        )
        if (tokens.isEmpty()) return

        val (title, body) = buildMessage(event)  // ↑/↓ 방향 포함
        sendPush(tokens, title, body, event)
    }
}
```

**AFTER_COMMIT 필요성**: Batch 계산 중 트랜잭션이 실패하면 점수가 저장되지 않는다.  
이때 푸시가 발송되면 안 되므로 커밋 이후 실행을 보장한다.

### 4-6. DB 스키마 변경

```sql
-- V19__add_grade_to_behavior_scores.sql
ALTER TABLE investment_behavior_scores
    ADD COLUMN IF NOT EXISTS grade VARCHAR(20);
```

기존 레코드는 `grade = NULL`로 남는다. 조회 시 `grade ?: BehaviorGrade.fromScore(score)`로 폴백한다.

### 4-7. 발화 시점

| 시나리오 | 발화 여부 |
|---|---|
| 점수 변동, 등급 동일 (`GOOD 65 → GOOD 72`) | ❌ 발화 안 함 |
| 등급 상승 (`GOOD → GREAT`) | ✅ 상승 알림 |
| 등급 하락 (`GOOD → FAIR`) | ✅ 하락 알림 |
| 최초 계산 (previousGrade = null) | ✅ 첫 등급 알림 |
| Batch 트랜잭션 롤백 | ❌ AFTER_COMMIT이므로 발송 안 됨 |
| 디바이스 토큰 없음 | ❌ 조기 리턴 |

---

## 5. Outbox Pattern — Kafka 외부 발행

Spring Modulith `@Externalized`를 이용해 인-프로세스 이벤트를 Kafka로 원자적으로 발행한다.

### 5-1. 왜 단순 Kafka publish가 아닌가

DB 커밋 전 Kafka 발행 → DB 롤백 시 이미 발행된 이벤트를 되돌릴 수 없다.  
Outbox Pattern은 **이벤트를 DB에 먼저 쓰고(같은 트랜잭션)** → 커밋 후 Kafka로 전달한다.

### 5-2. 이벤트 라우팅

```kotlin
@Externalized("trading.order-filled::#{#this.userId}")
data class OrderFilledEvent(...)

@Externalized("trading.order-cancelled::#{#this.userId}")
data class OrderCancelledEvent(...)
```

- `"trading.order-filled"` — Kafka 토픽
- `"::#{#this.userId}"` — 파티션 키 (userId 기준 순서 보장)

### 5-3. 발행 흐름

```
submitOrder() 트랜잭션
  ├─ orders/fills/paper_accounts 쓰기
  ├─ publishEvent(OrderFilledEvent) → event_publication INSERT (completion_date = NULL)
  └─ COMMIT → Spring Modulith가 Kafka 발행 → completion_date = now()
```

Kafka 발행 실패 시 `completion_date`가 NULL로 남아 재처리 대상이 된다.  
`OutboxResubmissionConfig`가 5분마다 1분 이상 미완료인 레코드를 재시도한다.

### 5-4. 공통 설계 원칙 — 인-프로세스 vs. Kafka

monticker의 이벤트는 두 종류다:

| 구분 | 방식 | 예시 |
|---|---|---|
| **모듈 간 도메인 이벤트** | Spring ApplicationEvent | OrderFilledEvent, GradeChangedEvent |
| **외부 발행 (Outbox)** | Spring Modulith → Kafka | trading.order-filled, trading.order-cancelled |
| **서비스 간 데이터 스트림** | Kafka topic (직접) | market.ticks, market.events |

자세한 내용: [outbox-pattern.md](./outbox-pattern.md)

### 5-2. @Async 필요 조건

이벤트 리스너에 `@Async`를 붙이는 기준:

| 리스너 | @Async | 이유 |
|---|---|---|
| `OrderFilledEventListener` | ❌ 없음 | 원장 기록은 체결과 동일 트랜잭션에서 완료돼야 함 |
| `AlertEvaluator` | ✅ `alertDispatchExecutor` | 틱 파이프라인 블로킹 방지 |
| `GradeChangedEventListener` | ✅ `behaviorScoreExecutor` | Batch 병렬 계산 스레드 블로킹 방지 |

`@TransactionalEventListener` + `@Async` 조합 시 주의:  
`AFTER_COMMIT`에서 실행되는 `@Async` 핸들러는 **새 트랜잭션을 시작하지 않는다.**  
DB 쓰기가 필요하면 핸들러 내부에서 `@Transactional(propagation = REQUIRES_NEW)`를 명시해야 한다.

### 5-3. 이벤트 명명 규칙

```
{도메인명}{과거형동사}Event

OrderFilledEvent      ← 주문이 체결됨
OrderCancelledEvent   ← 주문이 취소됨
GradeChangedEvent     ← 등급이 변경됨
TickProcessedEvent    ← 틱이 처리됨
```

과거형을 사용하는 이유: 이벤트는 **이미 발생한 사실**을 나타낸다.  
명령(`PlaceOrderCommand`)과 명확히 구분된다.

---

## 6. 검토 결과 — 적용하지 않은 곳

### ② UserRegistered

`AuthService.signup()`은 User만 저장하고 지갑/워치리스트는 `getOrCreate` 패턴으로 첫 접근 시 자동 생성된다.  
Lazy initialization이 이미 역할을 하므로 이벤트 추가 시 복잡도만 늘어난다. **적용 불필요.**

### ③ BacktestCompleted

`QuantBacktestEngine.run()`은 순수 인-메모리 연산이라 응답이 빠르다.  
`@RateLimited(limit=10, windowSec=3600)`으로 남용을 방지하고 있다.  
**데이터 규모가 커져 처리 시간이 2초를 넘으면 재검토** — 그 시점에 `202 Accepted + jobId` 패턴과 WebSocket 결과 push를 함께 설계하는 것이 자연스럽다.
