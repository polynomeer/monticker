# Order Saga — 주문 처리 분산 트랜잭션

## 배경

`MatchingService.submitOrder()`는 한 번의 요청에서 여러 단계를 수행한다:

1. 입력 검증
2. 현금 예약 (BUY)
3. 주문 레코드 생성
4. 체결 또는 오더북 등록
5. 현금 정산
6. 이벤트 발행

이 과정에서 Step 3 이후 예외가 발생하면 Step 2에서 차감된 현금이 환불되지 않아 사용자의 잔고가 영구 감소하는 버그가 발생할 수 있다.

**Saga 패턴**은 각 단계를 분리하고, 실패 시 성공한 단계를 역순으로 취소(보상 트랜잭션)함으로써 최종 일관성을 보장한다.

---

## 아키텍처

### 정방향 흐름

```
INIT
  │
  ▼ VALIDATE ── 수량/방향/종목 검증
  ▼ CASH_RESERVED ── BUY: paper_accounts.cash 차감 + saga.reservedAmount 기록
  ▼ ORDER_CREATED ── orders 테이블 INSERT, saga.orderId 기록
  ▼ ORDER_FILLED ── 조건 충족 시 즉시 체결 / 미충족 시 오더북 등록
  ▼ CASH_SETTLED ── BUY: 과납분 환불 / SELL: 체결금액 입금
  ▼ COMPLETED
```

### 보상 흐름 (실패 시 역순)

```
ORDER_FILLED 이후 실패 → 미체결 주문 취소 (오더북에서 제거)
ORDER_CREATED 이후 실패 → 주문 상태 CANCELLED
CASH_RESERVED 이후 실패 → saga.reservedAmount 환불
```

---

## 구현

### 사가 엔티티

```kotlin
// matching/saga/OrderSaga.kt
@Entity
@Table(name = "order_sagas")
class OrderSaga(
    val userId: Long,
    val stockId: Long,
    val side: String,
    val quantity: Int,
    var currentStep: SagaStep = SagaStep.INIT,
    var status: SagaStatus = SagaStatus.STARTED,
    var reservedAmount: BigDecimal? = null,  // 보상 트랜잭션 환불 금액
    var orderId: Long? = null,               // Step 3 이후 채워짐
)
```

### 오케스트레이터

```kotlin
// OrderSagaOrchestrator.execute()
fun execute(userId: Long, req: SubmitOrderRequest): SubmitOrderResponse {
    val saga = sagaRepo.save(OrderSaga(userId, req.stockId, req.side, req.quantity))
    return try {
        val response = runSteps(saga, userId, req)
        saga.status = SagaStatus.COMPLETED
        sagaRepo.save(saga)
        response
    } catch (ex: Exception) {
        compensate(saga)   // REQUIRES_NEW 트랜잭션
        throw ex
    }
}
```

### 보상 트랜잭션

```kotlin
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun compensate(saga: OrderSaga) {
    when {
        saga.currentStep >= SagaStep.ORDER_FILLED -> compensateFill(saga)   // 주문 취소
        saga.currentStep >= SagaStep.ORDER_CREATED -> compensateOrder(saga) // 주문 CANCELLED
        saga.currentStep >= SagaStep.CASH_RESERVED -> compensateCash(saga)  // 현금 환불
    }
}
```

보상 자체가 실패하면 `saga.status = FAILED`로 기록하고 로그를 남긴다.  
`FAILED` 상태 레코드는 모니터링 알림 대상이다 (수동 검토 필요).

---

## 영속성 — order_sagas 테이블

```sql
CREATE TABLE order_sagas (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          BIGINT        NOT NULL REFERENCES users(id),
    order_id         BIGINT        REFERENCES orders(id),
    stock_id         BIGINT        NOT NULL,
    side             VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity         INTEGER       NOT NULL,
    current_step     VARCHAR(40)   NOT NULL DEFAULT 'INIT',
    status           VARCHAR(20)   NOT NULL DEFAULT 'STARTED'
                                   CHECK (status IN ('STARTED','COMPLETED','COMPENSATING','COMPENSATED','FAILED')),
    reserved_amount  NUMERIC(18,4),
    error_message    TEXT,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    compensated_at   TIMESTAMPTZ
);

-- 미완료 사가 탐색 (부분 인덱스)
CREATE INDEX idx_order_sagas_incomplete
    ON order_sagas (status, started_at)
    WHERE status IN ('STARTED', 'COMPENSATING');
```

---

## 복구 스케줄러

### 필요성

기동 중 JVM crash가 발생하면 `currentStep`이 중간 상태에서 고정된다.  
재시작 후 영구적으로 미완료 상태가 될 수 있다.

### 구현

```kotlin
// OrderSagaOrchestrator.recoverIncomplete()
@Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
fun recoverIncomplete() {
    val stale = sagaRepo.findIncomplete(Instant.now().minusSeconds(300))
    stale.forEach { saga -> compensate(saga) }
}
```

- `initialDelay = 120_000`: 기동 직후 정상 사가가 5분을 넘기 전에 실행되지 않도록 2분 대기
- `findIncomplete(before)`: STARTED/COMPENSATING 상태이면서 `started_at < before`인 레코드 반환

---

## 상태 전이도

```
                      ┌──────────────┐
                      │   STARTED    │
                      └──────┬───────┘
                             │ 단계 진행 중
                             │
                ┌────────────┴────────────┐
                │ 성공                    │ 실패
                ▼                         ▼
         ┌──────────┐            ┌──────────────┐
         │COMPLETED │            │ COMPENSATING │
         └──────────┘            └──────┬───────┘
                                        │
                          ┌─────────────┤
                          │ 보상 성공   │ 보상 실패
                          ▼             ▼
                   ┌──────────┐  ┌────────┐
                   │COMPENSATED│  │ FAILED │ ← 수동 검토
                   └──────────┘  └────────┘
```

---

## 설계 결정

### Choreography vs. Orchestration

Choreography(이벤트 체인)는 각 서비스가 이벤트를 소비해 다음 단계를 실행하는 방식이다.  
monticker는 현재 모놀리스 구조이므로 **Orchestration**을 선택했다.

- 단일 오케스트레이터(`OrderSagaOrchestrator`)가 흐름을 명시적으로 제어
- 보상 순서와 조건이 한 파일에 집중되어 추적이 쉬움
- MSA 전환 시 각 단계를 원격 호출로 교체하면 Choreography로 자연스럽게 전환 가능

### @Transactional 경계

```
MatchingService.submitOrder()  @Transactional
  └─ OrderSagaOrchestrator.execute()  (동일 트랜잭션 참여)
       └─ runSteps()                  (동일 트랜잭션)
       └─ compensate()                @Transactional(REQUIRES_NEW)
            ← 원래 트랜잭션이 롤백되어도 보상은 별도 트랜잭션에서 커밋
```

보상 트랜잭션이 `REQUIRES_NEW`인 이유:  
원래 트랜잭션이 예외로 롤백 중일 때 보상(환불 등)은 **반드시 커밋**되어야 한다.

---

## 관련 문서

- [matching-engine-clob.md](./matching-engine-clob.md) — 주문 체결 엔진
- [eda-event-driven-architecture.md](./eda-event-driven-architecture.md) — 주문 이벤트 흐름
- [resilience-patterns.md](./resilience-patterns.md) — 신뢰성 패턴 모음
