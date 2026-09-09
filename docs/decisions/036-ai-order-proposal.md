# ADR-036: AI 주문 제안 (Order Proposal) — 모의투자 한정, 수동 요청

## Status
Accepted

## Context

`docs/product.md`의 로드맵에는 "AI 자동 매수/매도"에 대한 원칙만 명시돼 있었다 — "LLM은 주문 제안(Order Proposal)만 생성하고, 리스크 검증 → 사용자 승인 → 기존 Order Executor(OMS) 경유 없이는 절대 자동 실행하지 않는다." 실제 구현은 없었다(`docs/engineering-backlog.md` §4).

착수 전 기존 인프라를 조사한 결과:

- **AI 연동은 이미 존재한다.** `StockSummaryService`(`backend/api/.../ai/StockSummaryService.kt`)가 Anthropic Claude Haiku 4.5로 종목 요약을 생성 중이다. 이벤트 타임라인·뉴스·가격 동향(당일 시가/고가/저가, 전일 대비 등락률)을 모아 프롬프트를 구성하고, "투자 조언은 하지 말고 사실 기반으로만 설명해줘"를 프롬프트 끝에 명시하는 방식이다. 재시도/서킷브레이커는 없고 단순 try/catch로 실패 시 폴백 문자열을 반환한다.
- **주문 실행 경로가 두 갈래다.** `PaperTradingService.buy/sell`은 즉시 체결하는 단순 모의투자로 리스크 게이트가 없다(ADR-034에서 이미 재사용 금지로 결론남). `MatchingService.submitOrderChecked`(`@RiskChecked` AOP, `/api/matching/orders`)는 실제 주문북 매칭 엔진을 거치며 리스크 게이트가 적용된다 — AI 제안이 실제 주문으로 이어지려면 반드시 이 경로를 타야 한다.
- **`matching` 모듈은 이 저장소에서 특별한 제약이 있다.** `matching/package-info.java`: "다른 모듈과의 통신은 OrderFilledEvent, OrderCancelledEvent를 통한 이벤트 방식만 허용한다." `risk` 모듈이 애초에 matching 안에 있다가 분리된 이유도 "matching은 이벤트 전용 원칙을 지키고 있어 그 안에 계속 두면 brokerage가 matching 전체에 의존하게 된다"는 것이었다(`risk/package-info.java`). 즉 이 저장소는 **다른 모듈이 matching 서비스를 직접 호출하는 것 자체를 피해온 확립된 관례**가 있다 — `ai` 모듈이 여기 예외를 만들면서까지 `MatchingService`를 직접 호출하는 건 이 관례에 반한다.
- **재사용 가능한 UI 패턴이 있다.** `/matching` 페이지(`apps/web/src/app/matching/page.tsx`)가 이미 "종목·방향·수량 선택 → 리스크 사전 확인 → 주문 제출" 폼을 갖추고 있다.
- **법무 문서에 공백이 있다.** `docs/legal-review-brief.md`는 현재의 AI 요약 기능(개인정보 미전송, 사실 기반 프롬프트)과 Quant Lab/전략마켓의 유사투자자문업 신고 여부만 다룬다. AI가 매수/매도 "방향"을 제안하는 것 자체의 투자자문업 라이선스 이슈는 아직 검토 대상에 없다 — 사람이 처리해야 할 사항이라 이 라운드 범위 밖이며 `docs/human-action-items.md`에 별도로 반영한다.

사용자 확인(2026-09-09): 1라운드는 **모의투자 한정**(리스크가 가장 낮음), **사용자가 명시적으로 요청할 때만 생성**(스케줄러 없음)으로 스코프를 좁혔다.

## Decision

### 도메인 모델

신규 엔티티 `OrderProposal`(`com.monticker.api.ai` 패키지, 기존 `StockSummaryService`와 같은 모듈):

```
id, userId, stockId, side(BUY|SELL|HOLD), reasoning(TEXT),
status(PENDING|APPROVED|REJECTED), createdAt, expiresAt(createdAt+30분), decidedAt
```

제안 ≠ 주문 — 이 테이블은 "AI가 무엇을 제안했고 사용자가 어떻게 반응했는지"만 기록한다. 실제 주문 여부·체결 결과는 전혀 추적하지 않는다(아래 Consequences 참고).

### 생성 — `POST /api/ai/order-proposals { stockId }`

`StockSummaryService`와 동일한 데이터 소스(24시간 이벤트 타임라인, 최근 뉴스 5건, 가격 동향)로 프롬프트를 구성하되, 자유 텍스트 요약이 아니라 **구조화된 JSON**(`{"side": "BUY"|"SELL"|"HOLD", "reasoning": "..."}`)을 요청하고 파싱한다. 가격 동향 계산 로직(`PriceAction`)은 두 서비스가 동일한 알고리즘을 유지보수하게 되는 걸 막기 위해 `PriceActionService`로 추출해 공유한다.

LLM 미설정(`anthropicConfig.isConfigured == false`)이거나 호출/파싱 실패 시 제안을 생성하지 않고 명확한 에러를 반환한다 — `StockSummaryService`처럼 조용히 폴백 문자열로 넘기지 않는다(제안은 side가 없으면 애초에 의미가 없다).

### 승인/거부 — 실제 주문은 프론트가 기존 경로로 별도 제출

- `POST /api/ai/order-proposals/{id}/approve` — 제안 상태를 PENDING → APPROVED로 전이(만료됐거나 이미 처리된 제안이면 거부). **주문을 제출하지 않는다.**
- `POST /api/ai/order-proposals/{id}/reject` — PENDING → REJECTED.
- 프론트: "승인" 클릭 → approve 호출 성공 시 `/matching` 페이지의 기존 주문 폼(`OrderForm`)에 종목·방향을 프리필하고 포커스를 수량 입력으로 이동. 사용자가 수량을 직접 입력하고 기존 "매수/매도 주문" 버튼을 눌러야 비로소 `/api/matching/orders`(`MatchingService.submitOrderChecked`, `@RiskChecked`)를 거쳐 실제 주문이 제출된다.

이렇게 하면 `ai` 모듈의 `package-info.java`(`allowedDependencies = common, stock, event, news, marketdata`)를 전혀 바꾸지 않고 `matching`에 대한 새로운 직접 의존을 만들지 않는다 — 리스크 게이트는 항상 기존의 검증된 경로 그대로, 우회 지점이 없다. "제안 → 승인 → 사용자가 직접 수량을 확인하고 제출" 흐름은 자동 실행을 막는다는 원칙과도 더 정확히 맞는다(승인은 "이 방향에 동의한다"는 의사 표시일 뿐, 실제 체결까지 자동으로 이어지지 않는다).

### 안전장치

- LLM은 방향(BUY/SELL/HOLD)과 근거만 생성한다. 수량·가격은 절대 LLM이 정하지 않고 사용자가 주문 폼에서 직접 입력한다.
- 모의투자만 지원(실브로커리지 미지원 — 1라운드).
- 생성은 사용자의 명시적 요청 시에만(스케줄러 없음).
- 제안 TTL 30분 — 만료 후 승인 불가(가격이 급변할 수 있어 오래된 제안을 실행에 옮기는 걸 막는다).
- UI에 "이 제안은 투자자문이 아니며 참고용 시뮬레이션 정보입니다" 문구를 노출한다(ADR-023의 "투자 조언 아님" 원칙과 동일 선).

## Reasons

- **matching 모듈 우회 없이 리스크 게이트를 그대로 통과시키는 가장 단순한 방법**: 새 모듈이 matching을 직접 호출하는 예외를 만드는 대신, 프론트가 기존에 검증된 "리스크 사전 확인 → 주문 제출" 폼을 재사용하게 하면 백엔드 모듈 경계를 전혀 어지럽히지 않는다.
- **"승인 → 수량 직접 입력 → 별도의 명시적 제출 버튼"은 로드맵의 가드레일 원칙을 코드로 가장 직접적으로 구현한다** — 승인 자체가 주문을 트리거하지 않으므로 "승인 즉시 자동 체결"로 새는 경로가 구조적으로 없다.
- 기존 `StockSummaryService`의 데이터 소스·Anthropic 연동 패턴을 그대로 재사용해 새 인프라를 추가하지 않는다.

## Consequences

- OrderProposal과 실제 제출된 주문(`orders` 테이블) 사이에 서버 사이드 연결이 전혀 없다 — "이 제안이 실제로 몇 건의 진짜 주문으로 이어졌는지" 같은 통계는 이 설계로는 낼 수 없다. 필요해지면 프론트가 승인 직후 실제 주문 ID를 다시 제안에 기록하는 API를 추가해야 한다(1라운드에서는 과설계로 보고 제외).
- LLM 응답 파싱이 실패하는 경우(JSON 형식을 안 지켰을 때) 사용자에게는 그냥 "제안 생성 실패"로 보인다 — 재시도 로직은 없다.
- 투자자문업 라이선스 이슈에 대한 법률 검토가 이뤄지지 않은 상태로 배포된다 — `docs/human-action-items.md`에 반영.

## Revisit When

- 실브로커리지로 확장할 때 — `BrokerageService.submitOrder()`도 같은 "승인 → 프론트가 별도 제출" 패턴을 따를지, 아니면 서버 사이드에서 직접 호출할지(브로커리지 모듈은 matching과 달리 이벤트 전용 제약이 없다) 재검토.
- 정기 스캔 기반 자동 생성으로 확장할 때 — 알림/푸시 설계가 새로 필요하다.
- 법률 검토가 끝난 뒤 — UI 문구, 승인 플로우에 추가 고지가 필요한지 재확인.
- 제안↔실제 주문 연결 추적이 필요해질 때 — 승인 직후 프론트가 실제 orderId를 제안에 되돌려 기록하는 API 추가 검토.
