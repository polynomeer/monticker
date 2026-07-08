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

### ⑤ Value Object 추출 (우선순위: 낮음 — 현재 스케일에서 과잉)

현재 코드 곳곳에서 `BigDecimal`을 금액, 가격, 비율에 무분별하게 사용한다.

| 현재 | Value Object 후보 | 설명 |
|------|-------------------|------|
| `BigDecimal` (금액) | `Money` | 통화 단위, 음수 방지, 연산 메서드 |
| `BigDecimal` (가격) | `Price` | 0보다 커야 하는 불변식 |
| `conditionJson: String` | `AlertCondition` | JSON 파싱 + 검증 캡슐화 |
| `ruleDefinition: String` | `RuleDefinition` | JSON DSL + fingerprint 포함 |

**현재 권고:** YAGNI 원칙에 따라 스케일이 커지거나 `BigDecimal` 타입 혼동 버그가 발생할 때 도입.

---

### ⑥ Aggregate 경계 명확화 (참고)

현재 묵시적인 Aggregate 구조:

| Aggregate Root | Child Entity | 설명 |
|----------------|--------------|------|
| `Order` | `Fill` | Fill은 Order 없이 의미 없음 |
| `PaperAccount` | `PaperTrade` | 계좌 잔고와 거래 내역이 하나의 일관성 경계 |
| `RuleSet` | `QuantBacktestResult` | RuleSet이 변경되면 이전 백테스트 결과는 무효 |
| `User` | `AlertRule`, `WatchlistGroup` | 사용자 집계 루트 |

현재 코드는 이 경계를 Repository 레이어에서만 간접적으로 강제한다. DDD를 강화한다면 `Fill`을 `OrderRepository`를 통해서만 접근하도록 제한하는 것이 올바르나, JPA 편의성과의 트레이드오프가 있다.

---

## 적용 로드맵

```
Phase 1 (추천 — 즉시 적용 가능, 저위험)
  ① Order.fill() / cancel() / reject()
  ② PaperAccount.debit() / credit() / reset()

Phase 2 (선택적)
  ③ AlertRule.deactivate() / activate()
  ④ RuleSet.updateDefinition() / markBacktested() / publish()

Phase 3 (규모 성장 후)
  ⑤ Money/Price Value Object
  ⑥ Aggregate 경계 강화
```

Phase 1만 적용해도 `MatchingService`와 `PaperTradingService`의 핵심 복잡도가 줄고, 잘못된 상태 전이를 도메인 레이어에서 차단할 수 있다.

---

## 적용하지 않을 영역

| 모듈 | 이유 |
|------|------|
| `analytics/` | 계산 전용 서비스 — 엔티티가 상태를 가지지 않음 |
| `screener/` | 읽기 전용 쿼리 모델 — CQRS Read Side |
| `news/`, `event/` | 외부 데이터 수집 모델 — 도메인 로직 없음 |
| `marketdata/` | 시계열 데이터 — 불변 레코드 |
| `backtest/domain/strategies/` | 이미 전략 패턴으로 도메인 로직이 잘 분리됨 |
