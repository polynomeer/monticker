# ADR-103: 주문 상태 전이를 spring-statemachine에서 순수 전이표로

## Status
Accepted

## Context

모의투자 주문(`matching`)의 상태 전이 검증에 `spring-statemachine-starter` 4.0.2를 썼다(`OrderStateMachineConfig`·`OrderStateMachineService`, 호출부는 `MatchingService`·`LimitOrderFiller`·`OrderSagaOrchestrator`). Spring Boot 4 업그레이드(docs/dependency-upgrade-plan.md B4)를 조사하다가 이 라이브러리가 막힌다는 것을 확인했다.

- **유지보수 종료**: GitHub 저장소가 보관(archived) 처리됐고 README가 "Spring Statemachine is no longer maintained"라고 밝힌다. 4.0.2(2026-06)가 마지막 릴리스다.
- **Boot 4 미지원**: 4.0.2는 Spring Framework 6.2·Boot 3.5 autoconfigure 기준으로 빌드됐다. Boot 4에서 쓸 수 있는 5.x는 없다.

우리가 쓰는 범위는 아주 좁다: 상태 5개·이벤트 5개·전이 6개·가드 1개(남은 수량 > 0). 상태를 저장하지 않는다 — 호출마다 현재 상태를 넣어 머신을 재시작하고 이벤트 하나를 보낸 뒤 버린다. Order 엔티티의 `status`가 단일 진실 공급원이다. 라이브러리는 사실상 "허용되는 전이인가"를 판정하는 데만 쓰였다.

고려한 대안:
1. **순수 전이표(Kotlin `when`)** — 채택.
2. **다른 상태 머신 라이브러리**(KStateMachine 등) — 전이 6개에 의존성을 새로 들이는 건 과하고, 같은 일이 또 생길 수 있다(라이브러리 수명이 우리 업그레이드를 막는다).
3. **fork·재빌드해 계속 쓰기** — 유지보수 부담만 떠안는다.

## Decision

`OrderStateTransitions.next(current, event, remainingQty)` 순수 함수와, 같은 이름·같은 API의 `OrderStateMachineService.transition(...)`으로 바꾼다. 호출부는 바뀌지 않는다. `OrderStateMachineConfig`와 의존성을 지운다.

**교체 전에 라이브러리 구현의 동작을 기록했다**: 상태×이벤트×남은수량(0·5) 50조합의 결과와 메트릭 증가분을 라이브러리로 뽑고, 새 구현으로 같은 50조합을 돌려 비교했다 — **결과는 50조합 모두 같다.** 이 표가 `OrderStateMachineServiceTest`다.

기록하면서 드러난 두 가지:
- **가드 거부가 조용히 통과된다**: `PENDING --[PARTIAL_FILL]-->`을 남은 수량 0으로 보내면 예외 없이 `PENDING`을 돌려준다. 라이브러리의 `sendEvent`가 가드에 막힌 이벤트도 "수락"으로 응답했기 때문이다. **이 동작은 그대로 유지했다** — 교체와 동작 변경을 섞지 않는다. 의도된 동작인지는 호출부와 함께 따로 본다.
- **메트릭 잡음**: 라이브러리 시절 `order.state.transition` 카운터는 상태 복원을 위한 재시작 때문에 **호출마다(전이 실패 포함) `INITIAL→PENDING`을 1씩** 더했다. 실제로 PENDING으로 가는 전이는 없으므로 Grafana 거래 대시보드("주문 상태 전이")의 `to=PENDING` 시리즈는 전부 이 잡음이었다. 새 구현은 **실제로 바뀐 전이만** 센다(태그 `from`/`to`는 같다).

## Reasons

- 막힌 의존성을 없애 Boot 4 업그레이드를 연다.
- 전이 규칙이 한 화면의 `when` 식이라 읽고 검증하기 쉽다. 50조합 표 테스트가 규칙 전체를 덮는다.
- 요청마다 머신을 만들고 재시작하던 비용이 사라진다.

## Consequences

- 대시보드 "주문 상태 전이"의 `to=PENDING` 선이 0이 된다(원래 잡음). 다른 `to` 시리즈는 그대로다. 배포 시점 전후로 그래프가 꺾이는 이유를 운영자가 알 수 있게 이 ADR을 남긴다.
- 라이브러리가 주던 기능(계층 상태, 영속화, 분산 상태 머신)은 쓸 수 없다. 지금 쓰지 않고, 필요해지면 그때 고른다.
- 가드 거부의 조용한 통과는 그대로 남아 있다.

## Revisit When

- 주문 상태가 늘어나거나(예: 부분 취소, 정정) 상태 진입/이탈 동작이 필요해질 때.
- 가드 거부를 예외로 바꿀지 결정할 때 — 호출부(`LimitOrderFiller`·`MatchingService`)가 남은 수량 0의 `PARTIAL_FILL`을 보내는 경로가 있는지부터 본다.
