# ADR-035: Strategy Market 신호 접근 제어

## Status
Accepted

## Context

[engineering-backlog.md](../engineering-backlog.md) §5의 지시로 Strategy Market(판매자 등록 → 구매자 구독 → 신호 전달 → 수익 정산)이 실제로 프로덕션 수준까지 이어지는지 감사했다. 결과: 판매 등록(`StrategyMarketController.share`)은 소유권 검증·백테스트 상태 게이트까지 실제로 동작하고 테스트도 있다. 하지만 세 가지 실제 문제를 발견했다:

1. **`/topic/rulesets/{id}/signals`에 접근 제어가 전혀 없다.** `WebSocketConfig`(`backend/api/.../common/config/WebSocketConfig.kt`)의 STOMP 브로커는 애초에 인증 메커니즘 자체가 없다 — `ChannelInterceptor`가 하나도 없다. `ForwardTestService.evaluateOne()`이 이 토픽으로 신호를 발행하는데(ADR-024), 룰셋 ID만 알면 **구독료를 낸 적 없는 누구든** 같은 신호를 받을 수 있다. `docs/product.md`가 "tamper detection"이라고 부르는 SHA-256 fingerprint(`RuleSetService.sha256()`)는 실제로는 계산·저장만 될 뿐 어디서도 검증에 쓰이지 않는다 — 룰셋 원문 유출을 막는 건 fingerprint가 아니라 `findByIdAndUserId`(소유자 전용) 접근 제어인데, 정작 신호 자체는 이 보호를 안 받는다.
2. **구독 삽입과 결제가 원자적이지 않다.** `StrategyMarketController.subscribe()`가 `strategy_subscriptions`에 INSERT한 *뒤에* `creatorEarningsService.onStrategySubscribed()`를 호출한다 — 유료(price>0) 전략은 이 호출이 실패하는데(아래 참고), `@Transactional`이 없어 INSERT는 이미 커밋된 상태로 남는다. 결제가 실패해도 구독은 성립해버리는 데이터 정합성 버그다.
3. **`RuleSetService.delete()`가 마켓에 공유돼 구독자가 있는 룰셋도 그냥 지운다.** 신호의 원본이 사라지므로 구독자는 아무 알림도 안내도 없이 신호를 못 받게 된다.

**유료 결제(`CreatorEarningsService`→`PgClient.requestPayment()`)는 이번 범위에서 제외한다** — `TossPgClient.requestPayment()`가 "토스페이먼츠는 웹훅 기반 confirm 플로우를 사용하세요"라는 메시지와 함께 항상 실패하도록 만들어져 있고, 이건 Strategy Market만의 문제가 아니라 `SubscriptionService.subscribe()`도 똑같이 겪는 저장소 전체의 결제 연동 문제다(둘 다 이미 존재하는 confirm 기반 실결제 흐름을 안 쓰고 이 스텁을 직접 부른다). 제대로 고치려면 기존 구독 결제 전체를 다시 손대야 해서 범위가 크다 — **사용자가 명시적으로 이번 라운드에서 제외를 선택했다**. 대신 무료(price=0) 전략은 완전히 동작하는 경로로 만들고, 유료 전략은 "아직 지원 안 됨"을 프론트에서 명확히 표시해 사용자가 실패하는 결제를 실제로 시도하지 않게 막는다.

## Decision

1. **`RuleSetSignalAccessInterceptor`(신규, `quant/infrastructure/`)** — Spring `ChannelInterceptor`. `CONNECT` 프레임에서 `Authorization` 헤더의 JWT를 검증해 있으면 STOMP 세션의 `Principal`로 저장한다(없거나 무효해도 연결 자체는 허용 — `/topic/stocks/{id}` 등 다른 공개 토픽은 인증이 필요 없다). `SUBSCRIBE` 프레임에서 목적지가 `/topic/rulesets/{id}/signals` 패턴일 때만: Principal이 없으면 거부, 있으면 "룰셋 소유자이거나 `strategy_subscriptions`에 활성 구독이 있는지" 확인해 아니면 거부한다. `WebSocketConfig`(common 모듈)는 `List<ChannelInterceptor>`를 자동 주입받아 등록한다 — Spring Modulith 경계상 `common`이 `quant`를 직접 import하지 않고도(런타임 DI로만 연결) 인터셉터를 등록할 수 있다.
2. **`StrategyMarketController.subscribe()`에 `@Transactional` 추가** — 결제 실패 시 구독 INSERT가 같은 트랜잭션 안에서 롤백되도록 한다.
3. **`RuleSetService.delete()`에 구독자 수 가드 추가** — `strategy_market.subscribe_count`(해당 룰셋의 전체 구독 수) 합이 0보다 크면 삭제를 거부한다. "마켓에서 내리는" 별도 흐름(구독자 처리·환불 정책)은 이번 범위 밖이라 만들지 않는다 — 에러 메시지도 존재하지 않는 다음 행동을 암시하지 않도록 신중하게 작성한다.
4. **프론트: 가격 노출 + 유료 구독 비활성화.** `list()` 응답에 `price`, `isSubscribed`(현재 사용자 기준)를 추가한다. `price > 0`인 전략은 "유료 구독 준비 중"으로 표시하고 구독 버튼을 비활성화한다(실패하는 결제를 사용자가 실제로 시도하지 않도록). `price = 0`이고 구독 중인 전략은 "신호 보기"로 실시간 신호 패널을 열 수 있다 — `useForwardTestSignalsWs`가 이제 STOMP CONNECT에 JWT를 실어 보내도록 수정한다(1번 인터셉터가 이 헤더를 읽는다).

## Reasons

- **결제를 이번 범위에서 뺀 이유**: Context에서 이미 설명 — Strategy Market 하나만의 문제가 아니라 저장소 전체 구독 결제 플로우의 문제라 범위가 다르다. 사용자가 명시적으로 "보안 구멍부터"를 선택했다.
- **인터셉터를 `common`이 아니라 `quant`에 두는 이유**: 신호 접근 권한 판단은 `strategy_market`/`strategy_subscriptions`/`RuleSetRepository`를 알아야 하는 `quant` 도메인 로직이다. `common.WebSocketConfig`가 이걸 직접 알면 안 되므로(Spring Modulith 경계, ADR-019), `List<ChannelInterceptor>` 자동 주입으로 컴파일 타임 의존 없이 연결한다.
- **CONNECT 시점에 인증을 강제하지 않는 이유**: `/topic/stocks/{id}`(가격), `/topic/market` 등은 원래 로그인 없이도 봐야 하는 공개 데이터다. 이번 인터셉터가 모든 WS 연결에 로그인을 강제하면 그 기능들이 깨진다 — 그래서 CONNECT는 "있으면 신원 기록, 없으면 익명 허용"으로 두고, SUBSCRIBE 시점에 목적지별로만 판단한다.
- **`subscribe()`에 `@Transactional`을 추가한 이유**: 지금처럼 결제 실패가 구독 INSERT를 되돌리지 못하면, 유료 결제가 고쳐지기 전까지 "구독은 됐는데 결제는 실패"라는, 무료로 접근권만 얻는 상태가 이미 지금도 발생할 수 있다(price=0이 아닌데 subscribe_count가 늘고 subscription row가 남는다). 결제를 고치기 전에라도 이 정합성 문제만은 먼저 막아야 한다.
- **삭제 가드를 "마켓 해제" 흐름 없이 단순 차단으로만 만든 이유**: 구독자가 있는 전략을 안전하게 내리려면 환불·구독자 통지 정책이 필요한데 이건 결제 자체가 고쳐진 뒤에나 의미가 있는 결정이다. 지금은 "아예 못 지운다"가 가장 안전한 기본값이다.

## Consequences

- 프론트가 STOMP CONNECT마다 JWT를 보내게 된다 — 기존 익명 가격 조회 훅(`useStockPrice`, `useMarketPricesWs`)은 건드리지 않으므로 그쪽은 영향 없다.
- 유료 전략 구독은 여전히 실제로 안 된다 — 이번 ADR은 그걸 프론트에서 명확히 드러낼 뿐, 고치지 않는다. `TossPgClient.requestPayment()` 스텁과 `SubscriptionService.subscribe()`의 동일 문제는 별도 라운드 대상.
- 마켓에 올린 전략을 지우고 싶은 창작자가 구독자가 하나라도 있으면 영구히 못 지운다(내리는 기능이 없음) — 다음 라운드에서 반드시 다뤄야 할 사용성 공백으로 명시적으로 남긴다.
- SHA-256 fingerprint는 여전히 계산·저장만 되고 아무것도 검증하지 않는다 — 이번 ADR은 그 기능을 만들지 않았다(신호 접근 제어는 별개 메커니즘으로 해결했다).

## Revisit When

- 유료 결제 플로우 전체를 고칠 때 — `SubscriptionService`/`CreatorEarningsService` 둘 다 기존 confirm 기반 실결제 흐름(`PaymentWebhookController.confirm()`)으로 재작업. 고쳐지면 프론트의 "유료 구독 준비 중" 비활성화도 해제한다.
- "마켓에서 전략 내리기(구독자 있는 상태)" 기능이 필요할 때 — 환불 정책·구독자 통지를 먼저 설계해야 한다.
- fingerprint를 실제로 뭔가에 쓰기로 결정할 때(표절 탐지든 변조 탐지든) — 지금은 계산만 되는 미사용 필드다.
