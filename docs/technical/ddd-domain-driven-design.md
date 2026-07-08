# DDD 적용 검토 — monticker 전체 코드베이스

## 현재 상태: Anemic Domain Model

전체 도메인 엔티티를 분석한 결과, 프로젝트 전반이 **빈약한 도메인 모델(Anemic Domain Model)** 패턴을 따르고 있다.

- 모든 `domain/*.kt` 엔티티가 JPA 필드 + getter만 보유
- 비즈니스 로직 전체가 `application/*Service.kt`에 존재
- 도메인 객체의 상태 변경을 외부에서 직접 수행 (`order.status = OrderStatus.FILLED`)

### 예외: 도메인 로직이 이미 있는 곳

| 위치 | 내용 |
|------|------|
| `Order.remainingQty` | `quantity - filledQty` 계산 프로퍼티 |
| `BehaviorGrade.fromScore()` | 점수 → 등급 변환 팩토리 메서드 |
| `backtest/domain/strategies/*.kt` | `evaluate()` 메서드를 가진 전략 클래스들 (가장 DDD다운 부분) |

---

## DDD 적용 우선순위

### ① Order — 상태 전이 메서드 (우선순위: 높음)

**현재 문제:**

```kotlin
// MatchingService.kt:179-181
order.filledQty    = fillQty
order.avgFillPrice = fillPrice
order.status       = OrderStatus.FILLED
order.updatedAt    = Instant.now()

// MatchingService.kt:240
order.status = OrderStatus.CANCELLED
```

서비스가 엔티티 내부 상태를 4개 필드를 직접 조작한다. `Order`가 자신의 유효 상태를 보장하지 못한다.

**DDD 적용 방안:**

```kotlin
// Order.kt에 추가
fun fill(qty: Int, price: BigDecimal) {
    require(status == OrderStatus.PENDING || status == OrderStatus.PARTIALLY_FILLED)
    require(qty > 0 && qty <= remainingQty)
    filledQty += qty
    avgFillPrice = price
    status = if (remainingQty == 0) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED
    updatedAt = Instant.now()
}

fun cancel() {
    require(status in listOf(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED)) {
        "취소 불가 상태: $status"
    }
    status = OrderStatus.CANCELLED
    updatedAt = Instant.now()
}

fun reject(reason: String) {
    status = OrderStatus.REJECTED
    rejectReason = reason
    updatedAt = Instant.now()
}
```

**효과:** 서비스는 `order.fill(fillQty, fillPrice)` 한 줄로 호출. 잘못된 상태 전이가 컴파일 타임이 아닌 도메인 레이어에서 차단된다.

---

### ② PaperAccount — 잔고 조작 메서드 (우선순위: 높음)

**현재 문제:**

```kotlin
// PaperTradingService.kt:71
require(account.cash >= amount) { "잔고 부족" }
account.cash -= amount  // 직접 필드 수정

// PaperTradingService.kt:106
account.cash = BigDecimal("10000000")  // 리셋 로직이 서비스에
```

`PaperAccount`는 금융 집계 루트(Aggregate Root)임에도 잔고 검증과 변경을 외부에 위임한다.

**DDD 적용 방안:**

```kotlin
// PaperAccount.kt에 추가
fun debit(amount: BigDecimal) {
    require(amount > BigDecimal.ZERO)
    require(cash >= amount) { "잔고 부족: 필요 $amount, 보유 $cash" }
    cash -= amount
    updatedAt = Instant.now()
}

fun credit(amount: BigDecimal) {
    require(amount > BigDecimal.ZERO)
    cash += amount
    updatedAt = Instant.now()
}

fun reset() {
    cash = BigDecimal("10000000")
    updatedAt = Instant.now()
}

fun hasSufficientCash(amount: BigDecimal): Boolean = cash >= amount
```

**효과:** `require(account.cash >= amount)` 검증이 도메인 내부로 이동. 서비스는 오케스트레이션에만 집중한다.

---

### ③ AlertRule — deactivate 메서드 (우선순위: 중간)

**현재 문제:**

```kotlin
// AlertService.kt:43
rule.isActive = false
```

**DDD 적용 방안:**

```kotlin
// AlertRule.kt에 추가
fun deactivate() {
    isActive = false
    updatedAt = Instant.now()
}

fun activate() {
    isActive = true
    updatedAt = Instant.now()
}
```

**효과:** 작은 변경이지만 `updatedAt` 갱신 누락 같은 실수를 방지한다.

---

### ④ RuleSet — 버전 관리 및 상태 전이 메서드 (우선순위: 중간)

**현재 문제:**

```kotlin
// RuleSetService.kt:57
entity.ruleSetFingerprint = sha256(json)
entity.version           += 1

// RuleSetService.kt:124
ruleSet.status = RuleSetStatus.BACKTESTED
```

`sha256` 계산과 버전 증가는 `ruleDefinition`이 변경될 때 반드시 함께 발생해야 하는 불변식이다. 현재는 서비스가 이를 기억해야 한다.

**DDD 적용 방안:**

```kotlin
// RuleSet.kt에 추가
fun updateDefinition(newJson: String, fingerprint: String) {
    ruleDefinition = newJson
    ruleSetFingerprint = fingerprint
    version += 1
    updatedAt = Instant.now()
}

fun markBacktested() {
    status = RuleSetStatus.BACKTESTED
    updatedAt = Instant.now()
}

fun publish() {
    require(status == RuleSetStatus.BACKTESTED) { "백테스트 완료 후 배포 가능" }
    status = RuleSetStatus.ACTIVE
    updatedAt = Instant.now()
}
```

**효과:** "정의가 바뀌면 반드시 fingerprint와 version도 바뀐다"는 불변식이 도메인 내부에서 보장된다.

---

### ⑤ Value Object — Money / Price (Phase 3 구현 완료)

#### 도입 배경

`BigDecimal`이 금액(잔고·거래금액)과 가격(호가·체결가)에 동일하게 사용되어 타입 시스템이 두 개념을 구분하지 못했다.

```kotlin
// 도입 전 — price와 amount가 모두 BigDecimal, 혼용 가능
val amount = price.multiply(BigDecimal(quantity))   // price * qty → amount
account.cash -= amount                               // BigDecimal 직접 연산
```

실수로 `Price`를 `Money`가 기대되는 자리에 넘겨도 컴파일 오류가 없어서 런타임에서야 발견된다.

#### 설계

```kotlin
// common/domain/Money.kt
data class Money(val amount: BigDecimal) : Comparable<Money> {
    init { require(amount >= BigDecimal.ZERO) { "금액은 0 이상이어야 합니다" } }

    operator fun plus(other: Money) = Money(amount + other.amount)
    operator fun minus(other: Money) = Money((amount - other.amount).also {
        require(it >= BigDecimal.ZERO) { "잔액 부족" }
    })
    operator fun times(qty: Int) = Money(amount.multiply(BigDecimal(qty)))

    companion object {
        val ZERO = Money(BigDecimal.ZERO)
        val INITIAL_BALANCE = Money(BigDecimal("10000000"))
        fun of(amount: BigDecimal) = Money(amount)
        fun of(amount: String) = Money(BigDecimal(amount))
    }
}

// common/domain/Price.kt
data class Price(val amount: BigDecimal) : Comparable<Price> {
    init { require(amount > BigDecimal.ZERO) { "가격은 0보다 커야 합니다" } }

    fun toMoney(qty: Int): Money = Money(amount.multiply(BigDecimal(qty)))
}
```

**핵심 설계 원칙:**
- `Money`에서 `minus()`는 음수 결과 시 즉시 예외 → 잔고 부족이 연산 시점에 발견됨
- `Price.toMoney(qty)` → 수량과의 곱이 항상 `Money`를 반환, `Price * Price` 같은 무의미한 연산 불가
- `Money`와 `Price`는 서로 직접 연산 불가 (타입 불일치 컴파일 오류)

#### JPA 통합 — AttributeConverter

DB 스키마 변경 없이 기존 `NUMERIC(18,4)` 컬럼을 그대로 유지한다.

```kotlin
@Converter
class MoneyConverter : AttributeConverter<Money?, BigDecimal?> {
    override fun convertToDatabaseColumn(money: Money?) = money?.amount
    override fun convertToEntityAttribute(col: BigDecimal?) = col?.let { Money(it) }
}

// 엔티티에서
@Convert(converter = MoneyConverter::class)
@Column(nullable = false)
var cash: Money = Money.INITIAL_BALANCE
```

`@Embeddable`/`@AttributeOverride` 방식 대신 `AttributeConverter`를 선택한 이유:
- 컬럼명이 그대로 유지됨 (`cash`, `fill_price` 등 — 마이그레이션 불필요)
- nullable 필드(`limitPrice: Price?`)를 자연스럽게 지원
- 엔티티 생성자 시그니처 변경이 최소화됨

#### 적용 범위

| 엔티티 | 필드 | 타입 변경 |
|--------|------|-----------|
| `PaperAccount` | `cash` | `BigDecimal` → `Money` |
| `Order` | `limitPrice` | `BigDecimal?` → `Price?` |
| `Order` | `avgFillPrice` | `BigDecimal?` → `Price?` |
| `Fill` | `fillPrice` | `BigDecimal` → `Price` |
| `Fill` | `amount`, `fee` | `BigDecimal` → `Money` |

#### 서비스 변경 예시

```kotlin
// 도입 전
val amount = price.multiply(BigDecimal(quantity))   // BigDecimal * Int → BigDecimal
require(account.cash >= amount) { "잔고 부족" }
account.cash -= amount

// 도입 후
val amount = price.toMoney(quantity)                 // Price.toMoney(Int) → Money
account.debit(amount)                                // Money 타입 불일치 시 컴파일 오류
```

#### 트레이드오프

| 장점 | 단점 |
|------|------|
| `Money`/`Price` 혼용 컴파일 타임 방지 | DTO 직렬화 시 `.amount` 언래핑 필요 |
| 불변식(≥0, >0)이 생성 시점에 보장 | `JdbcTemplate` 직접 쿼리는 여전히 `BigDecimal` |
| 연산 의미가 명확 (`toMoney`, `debit`, `credit`) | `@Embeddable` 대비 집계 쿼리 어려움 |

**미도입 항목:** `AlertCondition`(JSON String), `RuleDefinition`(JSON String) — 현재 스케일에서 파싱 오버헤드 대비 이점이 적어 보류.

---

### ⑥ Aggregate 경계 강화 + CQRS Read Model (Phase 4 구현 완료)

#### 문제

`FillRepository`가 두 가지 책임을 동시에 가졌다.

```kotlin
interface FillRepository : JpaRepository<Fill, Long> {
    fun findAllByOrderId(orderId: Long): List<Fill>          // ✅ Order Aggregate 경계 내
    fun findAllByUserIdOrderByFilledAtDesc(userId: Long): List<Fill>  // ❌ 경계 우회
}
```

`userId`로 `Fill`을 직접 조회하는 것은 "Fill은 Order를 통해서만 접근해야 한다"는 Aggregate 규칙을 위반한다. 그러나 이를 엄격히 적용하면 사용자의 전체 체결 내역 조회 시 모든 Order를 불러와 Fill을 수집해야 하므로 N+1 문제가 발생한다.

#### 해결: CQRS Read/Write 분리

```
Write Side (FillRepository)          Read Side (FillQueryService)
──────────────────────────           ──────────────────────────────
findAllByOrderId(orderId)   →  Order Aggregate 경계 내 접근
                                     findByUserId(userId)  →  사용자 체결 내역
                                     findByOrderId(orderId, userId)  →  주문별 체결 내역
```

**FillRepository (Write Side)** — Order Aggregate 경계 내 접근만 허용:

```kotlin
interface FillRepository : JpaRepository<Fill, Long> {
    fun findAllByOrderId(orderId: Long): List<Fill>
    // findAllByUserIdOrderByFilledAtDesc 제거
}
```

**FillQueryService (Read Side)** — JDBC 기반 전용 조회:

```kotlin
@Service
@Transactional(readOnly = true)
class FillQueryService(private val jdbc: JdbcTemplate) {

    fun findByUserId(userId: Long): List<FillDto> =
        jdbc.query(
            "SELECT ... FROM fills WHERE user_id = ? ORDER BY filled_at DESC",
            { rs, _ -> FillDto(...) },
            userId,
        )

    fun findByOrderId(orderId: Long, userId: Long): List<FillDto> =
        jdbc.query(
            """SELECT f.* FROM fills f
               JOIN orders o ON o.id = f.order_id
               WHERE f.order_id = ? AND o.user_id = ?""",
            { rs, _ -> FillDto(...) },
            orderId, userId,
        )
}
```

`findByOrderId`에서 `JOIN orders ON o.user_id = ?`로 소유권 검증을 SQL 레벨로 이동 — 서비스에서 별도로 Order를 조회할 필요가 없어진다.

#### Write/Read 경로 분리 요약

| 경로 | 사용 위치 | 저장소 |
|------|-----------|--------|
| **Write** | `MatchingService.submitOrder()` | `FillRepository.save()` |
| **Read (Aggregate 내)** | `MatchingService.submitOrder()` 응답 구성 | `Fill.toDto()` (메모리) |
| **Read (Query)** | `getOrderFills()`, `getMyFills()` | `FillQueryService` (JDBC) |

#### 현재 Aggregate 구조

| Aggregate Root | Child Entity | 경계 강화 상태 |
|----------------|--------------|----------------|
| `Order` | `Fill` | ✅ CQRS로 분리 완료 |
| `PaperAccount` | `PaperTrade` | 📋 보류 (거래 내역 조회 패턴 동일) |
| `RuleSet` | `QuantBacktestResult` | 📋 보류 |
| `User` | `AlertRule`, `WatchlistGroup` | 📋 보류 |

---

## 적용 로드맵

| Phase | 대상 | 상태 |
|-------|------|------|
| **Phase 1** | `Order.fill/cancel/reject`, `PaperAccount.debit/credit/reset` | ✅ 완료 |
| **Phase 2** | `AlertRule.deactivate/activate`, `RuleSet.updateDefinition/markBacktested/publish` | ✅ 완료 |
| **Phase 3** | `Money`/`Price` Value Object (JPA AttributeConverter) | ✅ 완료 |
| **Phase 4** | Aggregate 경계 강화 + CQRS Read Model (`FillQueryService` 분리) | ✅ 완료 |

---

## 적용하지 않을 영역

| 모듈 | 이유 |
|------|------|
| `analytics/` | 계산 전용 서비스 — 엔티티가 상태를 가지지 않음 |
| `screener/` | 읽기 전용 쿼리 모델 — CQRS Read Side |
| `news/`, `event/` | 외부 데이터 수집 모델 — 도메인 로직 없음 |
| `marketdata/` | 시계열 데이터 — 불변 레코드 |
| `backtest/domain/strategies/` | 이미 전략 패턴으로 도메인 로직이 잘 분리됨 |
