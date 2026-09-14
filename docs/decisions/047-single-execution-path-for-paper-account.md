# ADR-047: 모의투자 계좌의 체결 경로를 매칭 엔진 하나로 통일한다

## Status
Accepted

## Context

모의투자 계좌(`paper_accounts`)에 돈을 움직이는 경로가 **둘**이고 서로를 모른다.

| | `POST /api/paper/buy|sell` (구 페이퍼 경로) | `POST /api/matching/orders` (매칭 엔진) |
|---|---|---|
| 체결 | 즉시, 현재가 | Saga([ADR-011](011-order-saga-orchestration.md)) — MARKET 즉시, LIMIT은 호가창 |
| 리스크 게이트([ADR-025](025-real-brokerage-order-safety-gate.md)) | **없음** | `@RiskChecked` 5개 룰 |
| 매도 시 보유 수량 확인 | 있음 | **없음 — 공매도가 된다** |
| 기록 | `paper_trades` | `orders` + `fills` |
| 포지션 프로젝션 `portfolio_positions` | 갱신 | **갱신 안 함** |
| T+2 정산([ADR-014](014-t2-paper-settlement-scheduler.md)) | 생성 | **생성 안 함** |
| 원장([ADR-013](013-append-only-ledger-wallet.md)) | `PaperTradeExecutedEvent` → FILL (`paper_trade_id` = paper_trades.id) | `OrderFilledEvent` → FILL (`paper_trade_id` = **fills.id**) |
| quant 추적·Kafka `trading.order-filled` | 없음 | 있음 |
| 프론트 | 포트폴리오 화면 `TradeModal`/`TradePanel` | 매칭 화면 |

`paper_trades`를 읽는 기능이 일곱 개다 — 포트폴리오·보유 평가액(`portfolio_positions` 경유), 거래 내역, 영수증,
감정 태그, 행동 점수, 일일 리플레이, 절세 시뮬레이션, T+2 정산. **매칭 엔진으로 산 종목은 이 일곱 곳 어디에도
없다.** 홈 포트폴리오에도, 지갑 보유 평가액에도, `HOLDING_DROP` 알림에도. 반대로 구 페이퍼 경로는 리스크 한도를
건너뛴다 — 한도의 존재 이유(ADR-025)가 화면 하나로 우회된다.

이 상태는 [ADR-043](043-ledger-pagination-and-reconciliation.md) 라이브 검증(원장의 `paper_trade_id`가 두 테이블을
가리킴), [engineering-backlog §9](../engineering-backlog.md)(DailyLossRule 수정 중 발견), 그리고 `reset()`이
`paper_trades`만 지우고 `fills`는 남겨 리스크 판정의 합집합 보유량이 초기화된 포지션과 어긋나는 문제로 세 번 드러났다.
DailyLossRule 수정에서 리스크 판정을 두 테이블의 **합집합**으로 바꾼 건 증상 처리였다.

### 후보

- **A) 브리지** — 매칭 체결마다 `paper_trades` 행을 미러링해 읽기모델을 살린다. 구 경로는 그대로 둔다.
- **B) 파사드** — `/api/paper/buy|sell`을 매칭 엔진의 MARKET 주문으로 위임하고, 체결은 매칭 엔진이 유일하게 만든다.
  모든 읽기모델은 `fills`를 읽도록 고친다(7곳).
- **C) B의 쓰기 경로 + A의 읽기모델** — 체결은 매칭 엔진만 만들되, paper 모듈이 `OrderFilledEvent`를 받아
  **계좌 실행 기록**(`paper_trades`)·포지션·정산을 만든다. 읽기모델 7곳은 그대로다.

## Decision

**C.** 매칭 엔진이 유일한 체결 경로이고, paper 모듈은 "계좌 기록" 모듈이 된다.

```
POST /api/paper/buy|sell ──▶ PaperTradingService (파사드)
                               │ matching::submit  (새 named interface — OrderSubmitter)
                               ▼
                         OrderSagaOrchestrator  ── 리스크 게이트 · 예약 · 체결 · 정산 (한 트랜잭션)
                               │ OrderFilledEvent (동기 @EventListener, 같은 트랜잭션)
                               ▼
                    paper.PaperExecutionListener
                      ├─ paper_trades INSERT (fill_id 링크)       ← 읽기모델 7곳이 보는 실행 기록
                      ├─ portfolio_positions 갱신
                      ├─ T+2 정산 생성
                      └─ PaperTradeExecutedEvent ──▶ wallet 원장 FILL (paper_trade_id = paper_trades.id)
```

1. **`matching::submit`** — `OrderSubmitter.submitMarket(userId, stockId, side, qty)` 하나를 노출하는 named interface.
   matching 모듈의 "인바운드는 이벤트만" 원칙([package-info](../../backend/api/src/main/java/com/monticker/api/matching/package-info.java))의
   예외이며, **사용자 요청 핸들러(paper 파사드)만** 쓴다. `allowedDependencies`가 강제한다 — ai 모듈 등 자동화 경로는
   이걸 나열할 수 없다([ADR-036](036-ai-order-proposal.md)의 결정은 유지된다).
2. **`PaperTradingService.buy/sell`은 파사드**다. 현재가·잔고·수량 확인·기록을 직접 하지 않고 MARKET 주문을 제출한다.
   응답 형태(`TradeResultResponse`)는 유지해 프론트는 바뀌지 않는다. 리스크 한도가 이제 이 경로에도 걸린다 — 422.
3. **`PaperExecutionListener`** — 동기 `@EventListener`(Modulith 비동기 리스너가 아니다). 사가 트랜잭션 **안에서**
   실행되므로 체결과 계좌 기록이 원자적이다: 기록이 실패하면 체결도 롤백된다. 계좌 정합성은 fail-closed가 맞다.
4. **`paper_trades.fill_id`**(UNIQUE, nullable) — 실행 기록과 매칭 체결의 링크. 기존 `fills` 중 미러가 없는 행은
   마이그레이션이 백필한다. 원장의 `paper_trade_id`는 이제 항상 `paper_trades.id`다 — wallet의
   `OrderFilledEventListener.onOrderFilled`(fills.id로 FILL을 쓰던 경로)는 제거한다(취소 리스너는 유지).
5. **매도 보유 수량 확인을 사가에 넣는다** — `portfolio_positions.net_qty`. 매칭 엔진이 공매도를 허용한 건 결함이다.
6. **리스크 판정은 `paper_trades`만 본다** — 모든 체결이 미러링되므로 합집합이 더는 필요 없고, `reset()`이
   `paper_trades`를 지우면 판정도 함께 초기화된다. `fills`/`orders`는 감사 기록으로 남는다.

## Reasons

- **A를 고르지 않은 이유**: 구 경로가 남으면 리스크 게이트 우회와 "즉시 체결 vs 사가"의 이중 의미론이 남는다.
  브리지만 놓으면 두 경로가 셋째 경로(브리지)를 더 얻을 뿐이다.
- **B를 고르지 않은 이유**: 읽기모델 7곳을 `fills`로 옮기는 건 T+2 정산·행동 점수·리플레이의 SQL과 테스트를 전부
  다시 쓰는 일이고, 그 대가로 얻는 건 "테이블 하나 적음"뿐이다. `paper_trades`는 이미 "계좌 관점의 실행 기록"이라는
  자기 의미가 있다 — `fills`는 매칭 엔진의 이벤트, `paper_trades`는 계좌의 거래다. 둘은 1:1이지만 같은 것이 아니다.
  실브로커 계좌([ADR-025](025-real-brokerage-order-safety-gate.md))도 `brokerage_orders`라는 자기 실행 기록을 가진다.
- **동기 리스너인 이유**: 비동기(`@ApplicationModuleListener`)면 파사드가 응답에 넣을 `tradeId`가 아직 없고,
  리스너 실패 시 "체결됐는데 포지션이 없는" 창이 생긴다. 계좌 기록은 체결의 일부다.
- **매칭 경계를 여는 이유**: 이벤트만으로는 "제출하고 결과를 받는" 요청-응답을 표현할 수 없다. 프론트가 HTTP로 하는
  일을 서버 안의 파사드가 못 할 이유가 없다 — 단, 누가 부를 수 있는지는 모듈 의존성으로 못 박는다.

## Consequences

- **`/api/paper/buy|sell`이 리스크 한도에 걸린다.** 이전엔 무제한이던 화면이 422를 받을 수 있다. 의도한 변화다.
- **`/api/paper/buy|sell`이 `orders`·`fills`도 남긴다.** 매칭 화면의 주문 내역에 포트폴리오 화면의 거래가 보인다 —
  같은 계좌이므로 맞다.
- **원장 `paper_trade_id`의 이중 의미가 사라진다** — ADR-043 V43 코멘트의 "매칭 경로는 fills.id"는 이 ADR 이전
  데이터에만 해당한다. 백필된 `paper_trades`와 그 시점의 원장 행은 링크되지 않는다(로컬 데이터뿐이라 무시).
- **`PaperTradeExecutedEvent`의 `tradeId`가 파사드 응답의 `tradeId`와 같다** — 영수증·감정 태그가 매칭 주문에도 붙는다.
- **paper 모듈이 `matching::submit`·`matching::api`(이벤트)에 의존한다.** matching은 paper에 의존하지 않으므로 순환은
  없다. 사가가 `portfolio_positions`를 JDBC로 읽는 건 이미 `paper_accounts`를 그렇게 읽는 것과 같은 수준이다.
- **`orders` 테이블의 `RiskChecked` 감사 로그가 모든 모의투자 주문을 덮는다.**
- 이 ADR은 **실브로커 경로를 건드리지 않는다.** 실거래는 `brokerage` 모듈이 별도 실행 기록을 가진다.

## Revisit When

- **LIMIT 주문을 포트폴리오 화면에서도 내고 싶을 때** — 파사드에 `orderType`·`limitPrice`를 열면 된다. 미체결 주문의
  포지션 표시(예약 수량)는 `portfolio_positions`에 없다.
- **paper 읽기모델이 `fills`의 정보(수수료 컬럼, 주문 링크)를 필요로 할 때** — 그때 B로 간다. 이 ADR의 `fill_id`
  링크가 그 전환의 발판이다.
- **자동 매매([ADR-036](036-ai-order-proposal.md) 이후 단계)가 서버 안에서 주문을 제출해야 할 때** — `matching::submit`을
  누가 쓸 수 있는지의 규칙을 다시 정한다. 지금은 사용자 요청 핸들러만이다.
