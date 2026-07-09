# ADR-011: Orchestration-based Saga for Order Processing

## Status
Accepted

## Context

`MatchingService.submitOrder()`는 다음 단계를 순서대로 수행한다:

1. 입력 검증
2. BUY 시 현금 차감
3. `orders` 테이블 INSERT
4. 체결 또는 오더북 등록
5. BUY 과납분 환불 / SELL 체결금 입금
6. 이벤트 발행

이 단계들은 하나의 `@Transactional` 메서드 안에 있었다. 문제는 다음과 같다:

- **부분 실패 시 보상 없음**: Step 2(현금 차감) 후 Step 3에서 예외가 나면 트랜잭션 롤백으로 현금이 환불된다. 하지만 Step 4(오더북 등록)는 in-memory 자료구조(`TreeMap` 기반 `OrderBook`)를 수정하는데, 트랜잭션 롤백이 오더북 상태를 되돌리지 않는다.
- **추적 불가**: 어느 단계에서 실패했는지, 보상이 완료됐는지 기록이 없다.
- **복구 불가**: 프로세스 재시작 후 중간 상태를 인식할 방법이 없다.

이 문제를 해결하는 일반적인 방법은 두 가지다:
- **Choreography**: 각 단계가 이벤트를 발행하고 다음 서비스가 소비해 실행. 결합이 느슨하지만 흐름 추적이 어렵다.
- **Orchestration**: 단일 오케스트레이터가 단계를 명시적으로 제어. 흐름이 한 파일에 집중된다.

## Decision

`OrderSagaOrchestrator`가 주문 흐름을 명시적으로 제어하는 **Orchestration 방식**의 Saga를 도입한다.

```
INIT → VALIDATED → CASH_RESERVED → ORDER_CREATED → ORDER_FILLED → CASH_SETTLED → COMPLETED
```

실패 시 역순 보상:
```
ORDER_FILLED  → 미체결 주문 취소 (오더북에서 제거)
ORDER_CREATED → 주문 상태 CANCELLED
CASH_RESERVED → reservedAmount 환불
```

Saga 상태는 `order_sagas` 테이블에 영속화해 복구 스케줄러(`recoverIncomplete()`)가 장애 후 미완료 사가를 탐지하고 보상을 재시도할 수 있도록 한다.

보상 트랜잭션은 `@Transactional(propagation = REQUIRES_NEW)`를 사용해 원래 트랜잭션 롤백과 독립적으로 커밋된다.

## Reasons

### Orchestration vs. Choreography

Choreography는 각 서비스가 자율적으로 이벤트를 소비해 실행하므로 MSA에서 자연스럽다.  
현재 monticker는 **모놀리스**이므로 단일 오케스트레이터가 더 적합하다:

- 모든 보상 로직이 `OrderSagaOrchestrator` 한 파일에 집중 → 추적·디버깅이 쉽다
- Choreography의 이벤트 체인은 디버깅 시 여러 리스너를 추적해야 한다
- MSA 전환 시 각 단계의 로컬 호출을 HTTP 호출로 교체하면 Choreography로 자연스럽게 전환 가능

### REQUIRES_NEW 보상 트랜잭션

원래 트랜잭션이 예외 상태에서 롤백 중일 때 보상(환불 등)은 반드시 커밋되어야 한다.  
동일 트랜잭션에서 보상을 시도하면 외부 트랜잭션 롤백과 함께 보상도 취소된다.

## Consequences

- **복잡성 증가**: 단순 `@Transactional` 메서드에서 오케스트레이터 패턴으로 전환된다. 코드량이 늘어나고 `order_sagas` 테이블이 추가된다.
- **Saga 테이블 누적**: 완료된 사가 레코드가 쌓인다. 주기적 정리가 필요하다.
- **보상 실패 = FAILED 상태**: 보상도 실패하면 `status = FAILED`로 기록하고 수동 개입이 필요하다. 모니터링 알림 연결이 필요하다.
- **오더북 롤백 한계**: 현재 in-memory 오더북은 트랜잭션으로 롤백이 불가능하다. Saga 보상에서 오더북 취소 메서드를 명시적으로 호출해 보상한다. 오더북이 DB 기반으로 전환되면 이 한계가 해소된다.
- **`MatchingService.submitOrder()`는 단순 위임**: 기존 비즈니스 로직을 오케스트레이터로 이전하고 서비스는 호출만 한다.

## Revisit When

- MSA 전환으로 각 단계가 별도 서비스로 분리될 때 → Choreography + Kafka로 전환하거나 Temporal 같은 Workflow 엔진 도입 검토.
- 사가 복잡도가 증가할 때(단계 추가, 조건 분기) → 상태 머신 기반 Saga 프레임워크 검토.
- FAILED 상태 사가가 빈번해질 때 → 자동 재처리 정책 및 Slack/PagerDuty 알림 연결.
