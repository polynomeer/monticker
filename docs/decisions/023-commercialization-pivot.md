# ADR-023: MVP 졸업 — 상용 서비스 전환

## Status
Accepted

## Context

`docs/product.md`의 "MVP Scope"는 지금까지 명확한 두 목록으로 나뉘어 있었다:

```
### Done
회원가입/로그인, 스크리너, 실시간 차트, 이벤트 타임라인, 알림, 백테스팅,
포트폴리오 리스크 지표, 모의투자(Paper Trading), VWAP/RSI/MACD ...

### Exclude from MVP
실제 주문 체결
Quant Lab 룰셋 빌더 UI
Strategy Market
AI 자동 매수/매도
소셜 커뮤니티
가상 투자 미션 / 친구 대결 리그
```

이 경계는 "개인 포트폴리오 프로젝트"(`docs/portfolio.md`) 단계의 스코프였다. 그런데
실제 코드베이스를 조사해보면 "제외" 목록의 상당 부분을 지탱하는 백엔드 인프라가 이미
프로덕션 수준으로 구현되어 있다:

- `matching/` 모듈: Spring Statemachine 기반 주문 상태머신, 보상 트랜잭션을 갖춘
  Saga 오케스트레이터(`OrderSagaOrchestrator`), 5종 리스크룰(일일손실·집중도·VaR·
  보유종목수·주문빈도, `RiskRuleQueryService`), `X-Idempotency-Key` 기반 멱등성
  필터(`common/idempotency/IdempotencyFilter`).
- `brokerage/` 모듈: 브로커 종류에 무관한 `BrokerageClient` 인터페이스와
  `KisBrokerageClient`/`MockBrokerageClient` 구현체, `application.yml`의
  `app.brokerage.mock.enabled` 프로퍼티로 전환 가능.
- `analytics/` 모듈: Markowitz 평균-분산 최적화(`PortfolioOptimizerService`)로
  목표 비중까지는 이미 계산됨.
- Quant Lab의 백테스트 엔진·포워드 테스트·룰셋 JSON 구조는 이미 설계·구현되어 있고,
  Strategy Market의 룰셋 보호(서버사이드 실행, SHA-256 fingerprint)도 설계돼 있음
  (`docs/product.md`).

즉 "제외" 목록은 "만들 수 없어서"가 아니라 "포트폴리오 프로젝트 스코프상 안 열어서"
빠져 있던 것에 가깝다. 이 시점에 마침 토스증권 Open API 검토(실주문/조건주문/KR+US
시세/투자자 동향/공매도·신용·대차 데이터 제공)가 이루어졌고, 사용자는 이 프로젝트를
포트폴리오 단계에서 실제 상용 서비스로 전환하기로 결정했다.

## Decision

monticker의 제품 스코프를 **MVP → 상용 서비스(production/commercial)** 로 전환한다.
`docs/product.md`의 "Exclude from MVP" 목록은 폐기하고, 아래 항목을 순차 활성화 로드맵으로
승격한다 (세부 우선순위·선행 과제는 `docs/product.md` "상용화 로드맵" 표 참고):

1. **실제 주문 체결 — BYOK(Bring Your Own Key) 모델.** monticker는 자체 브로커
   라이선스를 취득하지 않는다. 사용자가 본인 명의 증권 계좌(Toss Securities Open API
   또는 KIS Open API)의 API 키를 연결하고, monticker는 그 키로 사용자를 대신해 API를
   호출하는 클라이언트로만 동작한다. 새 주문관리시스템(OMS)을 만들지 않고, 기존
   `BrokerageClient` 인터페이스에 `TossBrokerageClient`를 추가하는 형태로 확장한다.
2. **실시간 시세 파이프라인의 실데이터 전환.** 현재 `market.ticks` Kafka 토픽은
   Mock/Go 합성 데이터로만 채워진다 — Toss/KIS 실시세 producer로 교체한다.
   `CandleAggregator`/`EventDetector`/`AlertEvaluator`는 토픽 소비자로서 변경 불필요.
3. **Quant Lab 룰셋 빌더 UI, Strategy Market** — 백엔드는 이미 준비됨, 프론트엔드
   활성화.
4. **리밸런싱 실행 자동화** — `PortfolioOptimizerService` 출력을 실제 diff·주문
   제출로 연결.
5. **조건주문(OCO/OTO, TP/SL)** — 기존 OMS를 대체하지 않고 그 위에 얹는 감시
   컴포넌트로 신규 설계.
6. **AI 자동 매수/매도 — 반드시 가드레일 적용.** LLM은 `Order Proposal`만 생성하고,
   `Risk Validation → User Confirmation → 기존 Order Executor(OMS)` 흐름을 거치지
   않고서는 절대 자동 실행하지 않는다. "감정 태그 ≠ 투자 조언" 원칙과 동일한 선을
   지킨다.
7. 소셜 커뮤니티, 가상 투자 미션/친구 대결 리그 — 낮은 우선순위로 검토만 유지.

아래 문서를 이 결정에 맞춰 갱신했다:
- `CLAUDE.md` — Project 섹션, stale한 "초기 상태" 문구 제거 및 상용화 단계 명시.
- `docs/product.md` — "MVP Scope" → "Product Scope"로 개편, Key Design Decisions에
  BYOK(#8)·AI 가드레일(#9) 원칙 추가.
- `docs/architecture.md` — Core Principle 상단에 단계 표기, 신규
  "Brokerage Adapter — BYOK Model" 섹션, ADR 표 갱신(013~023 누락분 포함).
- `docs/external-apis.md` — 신규 "§2 Brokerage / Order Execution" 섹션(Toss/KIS),
  "MVP Recommendation" 표기를 "Recommendation"으로 정정, Setup Checklist에
  BYOK 키 취급 원칙 추가.

## Reasons

- 주문 상태머신·Saga·리스크엔진·멱등성 처리 등 "제외" 목록을 막던 핵심 인프라가
  실제로는 이미 프로덕션 수준으로 구현되어 있다 — 이걸 계속 Paper Trading 전용으로만
  묶어두는 것은 이미 만든 가치를 썩히는 것이다.
- `BrokerageClient` 인터페이스가 브로커 비의존적으로 설계되어 있어(`BrokerageService`는
  인터페이스에만 의존), 새 브로커 어댑터 추가가 기존 코드 변경 없이 가능하다 —
  "새 엔진을 만드는" 위험한 결정이 아니라 "이미 있는 어댑터 계층에 구현체 하나
  추가하는" 낮은 리스크의 결정이다.
- BYOK 모델은 monticker가 브로커 라이선스 없이도 합법적으로 실주문을 중개할 수
  있게 해준다 — 사용자가 자기 명의 계좌의 API 키로 직접 거래하는 구조이므로,
  monticker는 금융투자업 인가가 필요한 "브로커"가 아니라 API 클라이언트/자동매매
  도구로 포지셔닝된다.
- AI 자동매매에 가드레일을 못 박는 이유: monticker는 이미 "룰셋 서버사이드 실행",
  "감정 태그 ≠ 투자 조언", "Strategy Market ≠ investment advisory" 같은 컴플라이언스
  원칙을 제품 설계 원칙으로 채택하고 있다. LLM에게 직접 주문 권한을 주면 이 원칙과
  정면으로 충돌한다.

## Consequences

- **보안 요건 상승**: 사용자별 브로커 API 키(appKey/appSecret)를 저장해야 하는데,
  현재 스키마/설계에 암호화 저장 메커니즘이 없다 — 실주문 연동 전 반드시 선행되어야
  하는 신규 작업.
- **동시성 안전성 요건 상승**: `OrderSagaOrchestrator.adjustCash`가 현재 row lock/
  버전 없는 plain `UPDATE`다. Paper Trading의 가짜 돈에서는 무해했지만, 실제 브로커
  계좌가 걸리면 동시성 레이스가 실제 금전 사고로 번질 수 있다 — 실주문 연동 전
  선행 수정 필요.
- **회복탄력성 격차**: `KisBrokerageClient`에 resilience4j 서킷브레이커가 없다
  (`TradingServiceClient`/`YahooFinanceOrderBookProvider`는 있음). 신규
  `TossBrokerageClient`는 이 패턴을 따라야 하고, 기존 `KisBrokerageClient`도
  실거래 전에 동일하게 보강해야 한다.
- **컴플라이언스 문서화 부담**: 실주문·AI 자동매매가 스코프에 들어오면 이용약관·
  "투자자문 아님" 고지·수수료/세금 안내 등 법적 문서가 필요해진다 (기존
  "과거 성과가 미래 수익을 보장하지 않습니다" 수준의 문구로는 부족할 수 있음 —
  법률 검토는 이 ADR의 스코프 밖이며 별도로 진행해야 한다).
- **가용성/운영 요건 상승**: 실제 시세·실제 주문이 걸리면 장중 다운타임의 대가가
  커진다 — 모니터링/알림/온콜 체계가 Paper Trading 단계보다 훨씬 엄격해져야 한다.
- 반대로, 이번 결정 자체는 **새 아키텍처를 요구하지 않는다** — 기존 모듈 경계
  (matching/brokerage/analytics/quant)를 그대로 확장하는 것이므로 구조적 재설계
  리스크는 낮다.

## Revisit When

- 사용자 수·거래량이 늘어 실제로 "투자중개업" 라이선스 경계에 걸리는지 법률 자문이
  필요해질 때 (BYOK 모델이 실제로 라이선스 예외에 해당하는지는 이 ADR에서 가정만
  했을 뿐 법률 검토를 거치지 않았다).
- Toss Open API의 WebSocket 지원이 공식화되면, REST 기반 시세 어댑터를 WebSocket
  구현체로 교체할 시점.
- AI 자동매매 가드레일(제안→검증→승인→실행)을 실제로 우회하려는 요구가 생기면 —
  이 ADR의 원칙(#6)을 먼저 재검토하고 새 ADR로 남겨야 한다.
