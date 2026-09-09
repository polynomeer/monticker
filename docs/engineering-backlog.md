# monticker — 상용화까지 코드로 구현 가능한 작업 목록

> Read this when: 다음에 뭘 구현할지 고를 때. 사람이 직접 해야 하는 일(계정/키 발급, 법률 자문 등)은 [human-action-items.md](human-action-items.md) 참고 — 이 문서는 코드/설계로 끝낼 수 있는 것만 담는다.

각 항목은 "왜 남았는지"와 "어디서 이어가는지"를 같이 적었다. 대부분 기존 ADR의 "Revisit When"에서 가져왔다 — 그 ADR을 먼저 읽고 시작할 것.

---

## 0. UI/UX 품질 (상용배포 준비보다 우선)

2026-09-09, 사용자 판단: 상용배포 준비(K8s 파이프라인 등 §7)보다 상용제품 수준의 UI/UX 품질을 먼저 끌어올리는 게 우선. [ui-benchmarks.md](ui-benchmarks.md)의 "monticker 실제 화면 갭 분석"(같은 날 작성)에서 외부 증권앱 15개 심층 벤치마크 체크리스트를 monticker 실제 코드와 대조해 나온 구체적 갭들 — 착수 전 [ui-benchmarks.md](ui-benchmarks.md)의 우선순위 후보 섹션을 먼저 읽을 것.

- [ ] **홈 대시보드 연결** — `PortfolioSnapshot`/`MarketSummary`/`RecentEvents`/`WatchlistSummary`/`TopMovers`(전부 [apps/web/src/components/home/](../apps/web/src/components/home/))가 이미 완성돼 실제 백엔드 API를 호출하도록 작성돼 있으나 [app/page.tsx](../apps/web/src/app/page.tsx) 어디에서도 import되지 않는 고아 코드로 방치돼 있음(전체 커밋 이력 확인, 죽은 지 오래됨). 지금 홈은 스크리너(종목 랭킹)만 있어 "5초 안에 내 자산 상태 파악"이 전혀 안 됨 — 벤치마크 보고서가 공통으로 지적하는 "홈은 콘텐츠 피드가 아니라 계좌 상태를 보여줘야 한다"는 원칙과 정확히 어긋남. 배치 전에 `PortfolioSnapshot.tsx` 등 일부 컴포넌트의 하드코딩된 hex 색상(`#ff5050`/`#4a8fd4`)을 `market-up`/`market-down` 토큰으로 교체 필요, API 응답 스키마가 지금 백엔드와 여전히 맞는지도 재검증 필요(오래 방치됐던 코드라 드리프트 가능성).
- [ ] **"최근 본 종목" 기능 신설** — 관심종목과 개념이 혼동되고 있음(코드에 "최근 본 종목" 자체가 없음). `localStorage`만으로 구현 가능해 서버 변경 불필요 — 관심종목([app/watchlist/page.tsx](../apps/web/src/app/watchlist/page.tsx))과 구분되는 별도 위젯으로 홈 또는 검색 결과 근처에 배치.
- [ ] **주문 사후관리 통합 뷰** — 일반 주문([app/brokerage/orders/page.tsx](../apps/web/src/app/brokerage/orders/page.tsx)), 조건부 주문([app/brokerage/conditional-orders/page.tsx](../apps/web/src/app/brokerage/conditional-orders/page.tsx)), 모의투자 체결([app/matching/page.tsx](../apps/web/src/app/matching/page.tsx))이 최소 2~3개 페이지로 흩어져 있어 미체결/부분체결/조건상태를 한곳에서 볼 수 없음. 외부 벤치마크 보고서의 P0 항목("주문 사후관리 일원화")과 정확히 일치 — 타임라인 형태의 통합 뷰 설계부터 시작.
- [ ] **관심종목 그룹 고도화** — 그룹 생성은 있으나([app/watchlist/page.tsx](../apps/web/src/app/watchlist/page.tsx)) 정렬·표시 컬럼·거래세션 필터·그룹 단위 알림 규칙 설정 UI가 없음.
- [ ] **종목 상세 내 크로스 내비게이션** — 차트 이벤트 마커, 뉴스, 이벤트 타임라인이 같은 페이지에 탭으로 공존만 할 뿐 서로 클릭으로 연결되지 않음(차트 마커 클릭 → 관련 뉴스로 스크롤 등). AI 요약(`SummaryPanel` bare 모드)에도 "AI 생성" 배지·근거 인용이 없어 객관 정보와 시각적으로 구분되지 않음.
- [ ] **주문 사전 검증 정보 보강** — 수수료가 체결 후에만 표시되고(사전 추정 없음), 환율/거래세션 상태 표시가 어떤 주문 폼에도 없음. 해외주식 주문 시 특히 중요.
- [ ] **알림 조건 확장** — 지금은 가격(이상/이하)·거래량급증뿐. RSI/이동평균 교차 같은 기술적 조건, "보유종목이 -N% 하락" 같은 보유상태 기반 알림 없음.
- [ ] **차트 고급 모드** — 드로잉 툴, 차트 위 주문선, 자유 지표 추가가 전혀 없음(RSI/MACD는 별도 서브탭일 뿐 메인 차트 오버레이 아님). 비용이 큰 항목이라 후순위 — 착수 시 [ui-benchmarks.md](ui-benchmarks.md)의 TradingView/Robinhood 항목(차트 위 매수/매도 퀵버튼) 참고.
- [ ] **목표/전략 단위 자산 뷰** — Quant Lab의 "전략" 개념과 실제 브로커리지 보유종목을 연결하는 뷰가 없음. 데이터 모델 변경 필요, 장기 항목.
- [ ] **정기매수(적립식) 기능** — 코드에 개념 자체가 없음. Trading 212 Pies/Trade Republic Savings Plan처럼 "주기 설정"이 아니라 "목표·배분" 관점으로 설계할 것([ui-benchmarks.md](ui-benchmarks.md) 참고). 신규 도메인 기능이라 장기 항목.
- [ ] **접근성 — 정보 밀도/큰 글씨 모드** — 다크·라이트 토글과 `aria-label`은 있으나 글자 크기·고대비 모드 옵션이 없음. mPOP 큰글씨홈 사례 참고.
- [ ] **신규 기능 온보딩 보강** — [app/onboarding/page.tsx](../apps/web/src/app/onboarding/page.tsx)가 관심종목/알림/Quant Lab 백테스트만 다루고 조건부 주문·AI 주문 제안 같은 최근 추가 기능은 미포함.

## 1. 실시간 시세 파이프라인 후속 (ADR-029~031)

- [ ] **KIS 41건 등록 한도가 커넥션당인지 앱키당 전역인지 실 서버로 확인** — 플랫폼 KIS 앱키가 발급되면([human-action-items.md §2-1](human-action-items.md#2-1-실시간-시세-플랫폼-키-adr-030031)) 가장 먼저 할 일. 결과에 따라 다음 항목(다중 커넥션 풀링)이 유효한 확장 경로인지, 아니면 여러 앱키가 필요한지가 갈린다. [ADR-030 Revisit When](decisions/030-kis-realtime-tick-ingestion.md#revisit-when)
- [ ] **KIS/Toss 다중 커넥션·앱키 풀링** — 위 항목 확인 후. 지금은 KIS 21종목 + Toss(미국 전체+국내 최대 100) 정적 분할이라 국내 약 30종목이 여전히 Mock — 풀링으로 이 갭을 줄일 수 있다.
- [ ] **`KisOrderBookSubscriber` 재설계** — 기존 `LIMIT 100`을 20으로 낮춰 즉시 위험만 없앴을 뿐, 호가 구독 자체의 다중화·정확한 한도 재검증은 미완료. [ADR-030 Revisit When](decisions/030-kis-realtime-tick-ingestion.md#revisit-when)
- [ ] **KIS 해외(미국) 실시간 체결 TR 연동** — 지금 미국 51종목은 Toss만 커버. KIS 쪽 해외 실시간 체결 TR은 이번 세션에서 조사조차 안 했다 — 필요해지면 신규 조사부터.
- [ ] **Toss `personal:order` 채널 활용 검토** — 계좌별 주문 체결 푸시. 지금 `BrokerageService`는 REST 폴링(`getOrderStatus`)만 쓴다 — 실시간 채널로 대체할 수 있는지 검토. [ADR-031 Revisit When](decisions/031-toss-realtime-tick-ingestion.md#revisit-when)
- [x] **Netty `broadcast-gateway`(ADR-005) 채택 또는 제거 결정** — ✅ 제거로 결정, 완료(2026-09-08, [ADR-033](decisions/033-remove-netty-broadcast-gateway.md)). 프론트엔드 클라이언트 0건·CI 커버리지 0건·실검증 커밋 0건 확인 후 `services/broadcast-gateway` 삭제, 문서(architecture.md, technical/) 동기화.

## 2. 조건부 주문 후속 (ADR-032)

- [ ] **OTO(One-Triggers-Other) 구현** — 주문 "체결" 이벤트가 트리거라 가격 틱(`MarketTickReceivedEvent`)과는 다른 소스가 필요. `BrokerageOrder.fill()` 시점을 관찰하는 별도 이벤트/리스너 설계부터 시작. [ADR-032 Revisit When](decisions/032-conditional-orders.md#revisit-when)
- [ ] **동일 계좌의 타 채널(HTS 등) 거래와의 조율** — 지금 조건부 주문은 순수 monticker 내부 상태라 사용자가 HTS로 직접 거래하면 인지하지 못한다. 실시간 잔고/포지션 동기화 설계 필요.
- [x] **500/409 버그 수정** — ✅ 완료(2026-09-08, `485767e`). `GET /api/brokerage/account`가 미연동 신규 사용자에게 500을 반환하던 문제 — `GlobalExceptionHandler`의 409 키워드 매칭이 "없음"만 잡고 실제 예외 메시지의 "없습니다"는 놓쳤던 게 원인. 신규 가입 테스트 사용자로 라이브 확인.

## 3. 리밸런싱 실행 자동화

- [x] **실브로커리지 한정, 수동 실행** — ✅ 완료(2026-09-09, [ADR-034](decisions/034-rebalancing-execution.md)). 목표 비중 저장(`rebalance_targets`) → 실행 시점마다 `BrokerageService.getBalance()`로 diff 재계산 → 임계값 초과 종목만 SELL 먼저·BUY 나중 순서로 `submitOrder()`에 순차 위임(리스크 게이트 그대로 적용, 우회 없음). 라이브 검증: 리스크 한도 초과 leg는 정상 거부(`ConcentrationRule`), 정상 범위 leg는 실제 FILLED 주문까지 확인.
- [ ] **모의투자 리밸런싱** — `MatchingService.submitOrderChecked`/`OrderSagaOrchestrator` 경로로 한정해 별도 실행기 필요(`PaperTradingService.buy/sell`는 리스크 게이트가 없어 재사용 금지). [ADR-034 Revisit When](decisions/034-rebalancing-execution.md#revisit-when)
- [ ] **스케줄 기반 자동 실행** — 지금 만든 diff 계산+순차 실행 로직을 `@Scheduled` 잡에서 재사용, 트리거만 추가. [ADR-034 Revisit When](decisions/034-rebalancing-execution.md#revisit-when)
- [ ] **`PortfolioOptimizerService` 결과를 목표 비중에 바로 저장하는 편의 플로우** — 지금은 목표 비중을 수동 입력만 지원한다. 프론트에서 `/api/analytics/portfolio/optimize` 결과를 `/api/rebalance/target`에 그대로 전달하는 "최적화 결과로 저장" 버튼을 추가할 수 있다(백엔드 변경 불필요, 프론트 전용 작업).
- [x] **`PortfolioOptimization`/`AlertRule` jsonb 바인딩 버그 수정** — ✅ 완료(2026-09-09, `b105ce7`). `RebalanceTarget.weightsJson`에서 발견된 것과 같은 버그(`columnDefinition="jsonb"`만으론 부족, `@JdbcTypeCode(SqlTypes.JSON)` 필요)가 `AlertRule.conditionJson`, `PortfolioOptimization.weightsJson`/`universeJson`/`frontierJson`에도 실제로 있었음 — `POST /api/alerts/rules`, `GET /api/analytics/portfolio/optimize`·`/frontier`에서 라이브로 재현 후 수정 확인, 전체 API 테스트 451/451 통과.

## 4. AI 자동 매수/매도 (가드레일 필수)

- [x] **모의투자 한정, 수동 요청 트리거 — Order Proposal 1라운드** — ✅ 완료(2026-09-09, [ADR-036](decisions/036-ai-order-proposal.md)). `OrderProposal` 엔티티(제안 ≠ 주문, `order_proposals` 테이블)가 방향(BUY/SELL/HOLD)과 근거만 저장 — 수량·가격은 LLM이 절대 정하지 않고 사용자가 승인 후 주문 폼에서 직접 입력한다. 승인/거부는 제안 자체의 상태만 바꿀 뿐 주문을 제출하지 않는다 — `matching` 모듈이 "다른 모듈과의 통신은 이벤트만 허용"이라는 이 저장소의 확립된 경계를 지키기 위해, 실제 제출은 프론트가 기존 리스크 게이트 있는 `/api/matching/orders` 폼에 방향만 반영해 사용자가 직접 누르게 했다. `StockSummaryService`가 쓰던 이벤트/뉴스/가격 데이터 소스를 재사용했고, 가격 동향 조회 로직은 `PriceActionService`로 뽑아 두 서비스가 공유. 생성은 사용자가 명시적으로 요청할 때만(스케줄러 없음). 라이브 검증: unit 10/10 + 전체 API 스위트 462/462 + curl로 전 시나리오(생성 실패 안전 처리, 승인, 재승인 차단, 만료 차단, 소유자 격리 404, 거부) 확인 + 브라우저에서 버튼 클릭 → 에러 UI 표시까지 실제 확인.
- [x] **실브로커리지로 확장** — ✅ 완료(2026-09-09). 백엔드 변경 없음 — `OrderProposal`이 애초에 계좌를 모르는 구조(`stockId`만 앎)였고 `BrokerageService.submitOrder()`도 `MatchingService`처럼 "사용자 계좌"를 내부에서 resolve하므로 같은 "승인 → 프론트가 별도 제출" 패턴이 그대로 적용됨. `AiProposalCard`를 `components/ai/OrderProposalCard`로 공용화해 `/brokerage/orders`에도 재사용, 실계좌용 강화 문구 적용. mock 브로커리지로 라이브 검증: 제안 승인(주문 미제출) → 승인된 방향으로 별도 제출한 실주문이 리스크 게이트 통과 후 FILLED. [ADR-036](decisions/036-ai-order-proposal.md)
- [ ] **정기 스캔 기반 자동 생성** — 스케줄러가 주기적으로 종목을 훑어 제안을 만드는 방식. 알림/푸시 설계 필요.
- [ ] **투자자문업 라이선스 이슈 법률 검토 반영** — [human-action-items.md](human-action-items.md)에 반영 필요(사람이 처리할 일). 검토 결과에 따라 UI 문구·승인 플로우에 추가 고지가 필요할 수 있음.

## 5. Strategy Market 실 배포 마무리

룰셋 보호(서버사이드 실행, SHA-256 fingerprint)는 [ADR-023](decisions/023-commercialization-pivot.md)에서 이미 설계됨 — 감사(2026-09-09) 결과 실제 마켓 플로우에서 진짜 문제 3건 발견, 그중 보안 구멍(STOMP 인가 누락)은 마무리, 결제는 범위 밖으로 남음:

- [x] **`/topic/rulesets/{id}/signals` STOMP 인가 누락 수정** — ✅ 완료(2026-09-09, [ADR-035](decisions/035-strategy-market-signal-access-control.md)). 누구나 어떤 룰셋의 신호든 구독 가능했던 문제. `RuleSetSignalAccessInterceptor`가 CONNECT의 JWT로 신원 확인 후 소유자/유료 구독자만 SUBSCRIBE 허용. 같이 고침: `subscribe()` `@Transactional`화(결제 실패 시 구독 row 고아 방지), `RuleSetService.delete()`가 활성 구독자 있는 룰셋 삭제 거부, 프론트 가격/구독 상태 표시 + `onStompError`로 거부 상태 노출. 라이브 검증: unit 7/7 + 실 서버 raw WebSocket으로 익명 SUBSCRIBE가 실제 STOMP ERROR 프레임으로 거부됨 확인.
- [ ] **Toss 결제 연동** — `TossPgClient.requestPayment()`가 항상 실패하는 스텁(웹훅 기반 confirm 플로우 필요, 지금 구조는 서버 주도 동기 호출을 가정). 유료 구독 버튼은 "준비 중"으로 명시적으로 막아둔 상태([ADR-035](decisions/035-strategy-market-signal-access-control.md) Context). 프론트 SDK 위젯 → `paymentKey` → 서버 `confirmPayment()` 플로우로 재설계 필요 — 이 저장소 전체(PG 연동)에 걸친 더 큰 작업이라 별도 라운드로 분리.
- [ ] **판매자가 활성 구독자 있는 전략을 마켓에서 내리는 플로우** — 지금은 룰셋 삭제만 막았을 뿐(위 항목), "마켓 등록 취소"에 해당하는 별도 액션 자체가 없다. `strategy_market` row를 비활성화하고 기존 구독자에게 알리는 흐름 설계 필요.
- [ ] **`RuleSetDocument.fingerprint`(SHA-256) 실사용처 연결** — 필드는 존재하지만 마켓 구독/신호 전달 경로 어디에서도 검증에 쓰이지 않음 — 애초 설계 의도(룰셋 무결성 증명?) 재확인부터 필요.

## 6. QA/CI

- [x] **백엔드(api/worker/trading-service/quant-engine) 전체 스위트 최초 일괄 실행 및 확인** — ✅ 완료(2026-09-09). 개별 라운드마다 부분 확인은 했지만 4개 모듈을 한 번에 돌린 건 이번이 처음 — 실제 버그 2건 발견·수정: (1) `PostgresIntegrationTest`의 companion object 공유 Testcontainers 컨테이너가 `@Container`의 클래스별 afterAll 훅 때문에 먼저 끝난 서브클래스가 아직 실행 중인 다른 서브클래스의 컨테이너를 죽이는 레이스 — 3연속 전체 스위트 실행마다 매번 다른 클래스가 "Connection refused"로 실패, 수동 start()+stop() 미호출로 전환 후 3연속 클린 확인. (2) `apps/mobile`이 `@react-navigation/native@^6.1.0`을 선언했지만 실제 라우터인 `expo-router@~4.0.0`은 내부적으로 v7을 요구해 두 메이저 버전이 동시 설치되며 `@types/react` 18/19가 뒤섞여 `tsc --noEmit`이 깨짐 — 선언 버전을 v7로 정정. 최종: api 469/469, worker 80/80, trading-service 21/21, quant-engine 22/22, web lint+unit 37/37(사용 안 하는 `fmt` 헬퍼 lint 에러도 같이 수정), mobile typecheck 클린.
- [x] **`next build`(그리고 순수 `tsc --noEmit`)가 `apps/web/src/app/subscription/billing/callback/page.tsx`의 유일한 `<Suspense>` 사용처에서 타입 에러로 실패** — ✅ 완료(2026-09-09). 근본 원인은 `next@15.1.0`/react 버전 스큐가 아니라 pnpm 워크스페이스의 **팬텀(ambient) `@types/react` 호이스트 슬롯 충돌**이었다: `apps/mobile`이 React 18 타입을, `apps/web`이 19.x를 쓰는 워크스페이스에서 pnpm이 `node_modules/.pnpm/node_modules/@types/react`(호이스트 폴더)에 18.3.31을 올렸는데, `next`/`@tanstack/react-query`/`next-themes`처럼 `@types/react`를 자기 package.json에 아예 선언하지 않고 `/// <reference types="react" />` 등으로 앰비언트 해석에 의존하는 패키지들이 자기 node_modules에서 못 찾으면 그 호이스트 폴더까지 타고 올라가 18.x를 집어드는 바람에, 앱이 직접 import하는 19.x `@types/react` 인스턴스와 타입 아이덴티티가 갈라져 `ReactNode`/`ReactPortal` 구조 불일치가 났다(`Suspense`가 유일하게 걸린 건 우연히 `children: ReactNode` 형태로 이 불일치를 표면화하는 유일한 지점이었을 뿐, `QueryProvider`/`ThemeProvider`에서도 동일 원인의 별개 에러가 숨어 있었음). `packageExtensions`로 `next`/`@tanstack/react-query`/`next-themes` 세 패키지에 `@types/react: ^19.2.18` 의존성을 명시적으로 주입해 각자 자기 node_modules 안에서 바로 해석되게 하여 팬텀 호이스트 폴더를 우회했다(apps/mobile은 건드리지 않아 React 18 타입 그대로 유지, `pnpm --filter mobile exec tsc --noEmit` 클린 재확인). **주의**: 이 설정을 처음엔 `pnpm-workspace.yaml`에 넣었는데, `overrides`/`packageExtensions`가 `pnpm-workspace.yaml`로 옮겨진 건 pnpm v11부터이고 이 저장소는 CI 3개 워크플로우와 `apps/web/Dockerfile` 전부 `pnpm@9`를 고정해서 쓰기 때문에 v9는 그 위치를 조용히 무시했다(로컬에서 pnpm 11로 검증하고 실제 CI가 쓰는 pnpm 9로는 재검증을 안 해서 처음엔 놓쳤음) — 새로 만든 루트 `package.json`의 `"pnpm"` 필드로 옮기고 `apps/web/Dockerfile` deps 스테이지에 그 파일을 COPY하도록 고쳐서 실제로 pnpm@9 하에서 적용됨을 확인했다. 결과(pnpm@9.15.9로 재검증): `pnpm --filter web build` 클린, `pnpm --filter web exec tsc --noEmit` 클린, `pnpm --filter web test:e2e`(로컬에서 API`SERVER_PORT=8095`+Web`PORT=3015` 기동, `E2E_BASE_URL`로 구동) 4/4 통과, `docker build -f apps/web/Dockerfile --target deps`도 `--frozen-lockfile`로 클린. task_29c57f70 종료.

- [x] **`docker build --target builder`가 `Cannot find module '@monticker/types'`로 실패** — ✅ 완료(2026-09-09, `17bb0f6`/`e2fd792` — 두 세션이 병렬로 같은 원인을 각각 고침, 병합 시 `17bb0f6` 방식 채택). 실제 원인은 워크스페이스 심볼릭 링크 문제가 아니라 `.dockerignore`였다 — `packages/types/*` + `!packages/types/package.json`이 `src/`를 통째로 빌드 컨텍스트에서 제외했는데, Docker의 ignore negation은 상위 디렉토리가 이미 제외 패턴에 매치되면 그 안의 개별 파일 negate가 적용되지 않는다(gitignore와 동일 특성) — 그래서 `Dockerfile`의 `COPY packages/types packages/types`가 실제로는 `package.json`만 복사하고 `src/`는 아예 못 봤다. `packages/types`는 빌드 스텝 없이 `main: "./src/index.ts"`를 직접 참조하는 소스 전용 패키지라 애초에 이 제외 자체가 잘못된 최적화였다 — 두 줄 완전 삭제(`**/node_modules`가 이미 `node_modules`는 제외해줌; `!packages/types/src` 식으로 예외를 추가하는 대안도 있었지만, 그러면 향후 `packages/types`에 새 파일이 생길 때마다 재발할 수 있어 완전 삭제 쪽을 택함). 라이브 검증: `docker build --target builder`/전체 3-스테이지 빌드 모두 성공, 실제 컨테이너 기동 후 `/`에서 200 확인.
- [x] **위 Suspense 수정(PR #63)이 실제 GitHub Actions에서 한 번도 검증되지 않았음을 발견 + `next` 보안 패치** — ✅ next 패치 완료(2026-09-09), audit 게이트 자체는 별도 항목(아래 §audit 게이트 재설계)에서 완료. PR #63의 실제 CI 런 3회를 확인한 결과 매번 `Install dependencies` 다음의 `pnpm audit --audit-level=high` 스텝에서 먼저 실패해 `Lint`/`Test`/`Build`가 전부 skip됐다 — 즉 위 Suspense 타입 수정은 로컬 검증만 있었을 뿐 실제 CI의 `next build`로는 한 번도 확인된 적이 없었다. 게다가 PR #63은 CI가 계속 빨간불인 채로 이미 main에 머지됐다. 원인은 `apps/web/package.json`이 `next`를 캐럿 없이 `15.1.0`으로 정확히 고정해뒀던 것 — 이 버전에 RCE(React flight protocol, Windows 호스트, AVIF Image Optimization), 인증 우회(Middleware), 다수의 SSRF/DoS 등 critical 5건을 포함해 75건의 알려진 취약점이 있었다(`web-ci.yml`은 `apps/web/**` 변경 시에만 트리거되지만 audit 자체는 워크스페이스 전체를 스캔하므로, 2026-09-07 이후 이 저장소를 거친 web 관련 PR이 거의 전부 이 지점에서 막혀 있었다). `next`/`eslint-config-next`를 `15.5.25`로 올리고(같은 메이저 내 최신 안정판), 루트 `package.json`의 `packageExtensions` 키를 `next@15.5.25`로 갱신, `next`가 번들한 `postcss@8.4.31`(자체 취약점 4건)도 `overrides`로 `next>postcss: >=8.5.23`으로 강제 — 검증: `pnpm audit`에서 `next` 관련 항목 0건, `pnpm --filter web exec tsc --noEmit` 클린(Suspense 포함), `pnpm --filter web build` 클린(모든 라우트 정상 생성), `pnpm --filter web lint`/`test` 클린(37/37), `pnpm install --frozen-lockfile` 클린, `docker build --target deps --frozen-lockfile` 클린, `pnpm --filter mobile exec tsc --noEmit` 무영향 확인.

- [x] **audit 게이트 재설계 — mobile 전이 의존성 패치 + CI 스코프 재설계** — ✅ 완료(2026-09-09, `b92c533`/`e8ae86c`). 위 항목에서 미룬 두 문제(mobile 전이 의존성 critical 1 + high 25건, `web-ci.yml`이 워크스페이스 전체를 스캔하는 구조적 한계)를 함께 해결. **(1) mobile 전이 의존성 패치**: 루트 `package.json`의 `pnpm.overrides`에 `postcss`/`@xmldom/xmldom`/`decode-uri-component`/`tar`/`uuid` 5개를 안전 버전으로 강제(`b92c533`) — `pnpm why --filter @monticker/mobile`로 모든 소비처가 안전 버전으로 재해석됨을 확인, `tsc --noEmit` 클린 재확인, 메이저 버전을 건너뛴 `tar`/`uuid`는 특히 위험해서 별도 런타임 스모크 테스트로 검증(`cacache`는 `tar`를 package.json에 선언만 하고 실제 코드 경로에서는 호출하지 않음을 소스 확인 후 `cacache.put`/`get` 라운드트립 테스트로 재확인, `@expo/bunyan`의 `uuid.v1()` 호출도 직접 실행해 정상 동작 확인). 이로써 `pnpm audit --audit-level=high` findings가 35건(critical 1 + high 25 + moderate 9)에서 `image-size` high 2건으로 감소 — 이 2건(GHSA-w3rx-r6r6-pgpr ICNS 파서 DoS, GHSA-5p2g-fcmc-qvqq JXL/HEIF 파서 DoS)은 advisory의 `patched_versions`가 `"<0.0.0"`(업스트림에 어떤 버전도 패치 없음)라 override로 원천적으로 해결 불가능함을 확인. **(2) CI 스코프 재설계**: pnpm v9의 `audit`는 `--filter`가 없어 항상 워크스페이스 전체 lockfile을 스캔하므로, mobile을 아무리 패치해도(위 `image-size`처럼) 워크스페이스 전체 기준으로는 절대 100% 그린이 될 수 없음이 구조적으로 확정됨 → 패치만으로는 해결 불가, CI 자체의 스코프 재설계가 필수라고 판단. `scripts/ci/audit-scope.js` 신규 작성 — `pnpm audit --audit-level=high --json` 결과를 파싱해 `findings[].paths`가 CLI로 지정한 워크스페이스 접두사(`apps/web`, `packages/`, `apps/mobile` 등)에 속하는 advisory만 게이트에 반영하고, `--allow <module>` 옵션으로 명시적 예외를 지원(`e8ae86c`). `web-ci.yml`은 `node scripts/ci/audit-scope.js apps/web packages/`로 교체(mobile 전용 findings는 더 이상 web PR을 막지 않음), `mobile-ci.yml`에는 신규 audit 스텝을 추가해 `node scripts/ci/audit-scope.js apps/mobile --allow image-size`로 스코프(`image-size`는 Metro가 개발자 자신의 로컬 에셋만 처리하는 빌드 경로라 실질 악용 가능성이 낮다고 판단해 명시적으로 예외 처리, 코드에 근거 주석 남김). 로컬 검증: web 스코프 findings 0건(exit 0), mobile 스코프는 `image-size` 2건이 예외 처리로 보고되며 exit 0, `--allow` 없이 돌리면 동일 2건이 게이트를 막아 exit 1이 되는 것까지 확인해 스크립트의 필터링·예외·실패 경로 전부 동작 검증.
- [x] **`echarts@5.6.0`의 XSS 취약점(위 항목에서 미룬 부분) 패치** — ✅ 완료(2026-09-09). `apps/web`이 echarts를 쓰는 5개 파일(`EChartsAdapter`/`VolumeChart`/`IndicatorChart`/`BacktestResultView`/`quant-lab/[id]`) 전부 `import("echarts")`로 표준 `echarts.init`/`setOption` API만 쓰고, 색상·범례 위치·툴팁 스타일을 커스텀 `theme` 객체로 명시 지정해 echarts 6.0의 breaking change(기본 테마 변경, 범례 기본 위치 하단 이동, 축 anti-overflow 기본 활성화)에 해당하는 "기본값 의존" 코드가 없음을 소스 확인. `echarts`를 `^5.5.0` → `^6.1.0`으로 올림 — 검증: `tsc --noEmit`/`build`/unit test(37/37) 클린, `pnpm audit`에서 echarts 항목 0건. 이 5개 컴포넌트에 자동 테스트가 전혀 없어(스냅샷·유닛 전무) 백엔드 전체 스택 대신 필요한 API(`/api/stocks/search`, `/candles`, `/orderbook` 등)만 흉내 낸 목 서버로 실데이터 형태를 흘려 브라우저에서 직접 확인: 캔들/거래량/RSI/MACD(범례 포함) 차트가 다크·라이트 테마 모두에서 정상 렌더링, echarts 관련 콘솔 에러 없음. `/backtest`(`BacktestResultView`)는 백엔드 POST 응답 스펙이 복잡해 실행까지는 안 해봤으나 동일한 `echarts.init`+bar/line 렌더링 경로를 다른 화면에서 이미 확인했으므로 리스크는 낮음.
- [ ] `scripts/load-test/k6-smoke.js` 부하테스트 시나리오 확장 — 지금은 스크리너/로그인/모의투자 주문 3종만. 실브로커 주문(BYOK) 경로, 조건부 주문 발동 경로는 아직 부하테스트 시나리오에 없음

## 7. 인프라 코드 작업 (프로비저닝 자체는 human-action-items.md, 코드/스크립트는 여기)

- [ ] **실 K8s 클러스터 배포 파이프라인** — 이미지는 GHCR까지 자동 푸시됨([.github/workflows/deploy-images.yml](../.github/workflows/deploy-images.yml)), 그 이미지를 실제 클러스터에 적용하는 CD 스텝(`kubectl apply`/ArgoCD/Flux 등)이 없음 — 클러스터가 실제로 생기면 이어서 작성
- [x] **PITR(WAL 아카이빙) 스크립트** — ✅ 완료(2026-09-09). "실 프로덕션 Postgres가 있어야 리허설 가능"이라는 전제가 틀렸음이 확인됨 — WAL 아카이빙은 Postgres 자체 기능이라 완전히 격리된 임시 Docker 컨테이너(dev DB는 건드리지 않음)로도 리허설 가능했다. `infra/db/pitr-basebackup.sh`/`pitr-restore.sh` 추가, 베이스 백업 → 카나리아 → 목표 시각 → 이후 데이터 → 목표 시각 복구 → 카나리아까지만 남고 이후 데이터 정확히 사라짐까지 실제로 확인([infra/db/README.md](../infra/db/README.md) 참고). 남은 건 실 프로덕션 Postgres에 `archive_mode`/`pg_hba.conf` 설정 적용뿐(`human-action-items.md`의 클러스터/DB 프로비저닝에 포함).

## 8. 우선순위 낮음 — 스펙부터 필요

아래는 "작업 단위"로 쪼갤 수 있을 만큼 구체적인 설계가 아직 없다. 착수 전 제품 스펙 논의(도메인 모델, 화면 흐름)부터 필요:

- [x] **소셜 커뮤니티 — 종목 커뮤니티 댓글 1라운드** — ✅ 완료(2026-09-09, [ADR-037](decisions/037-stock-community-comments.md)). 스펙부터 사용자와 논의해 범위를 좁힘: 트레이더 소셜 그래프나 범용 자유게시판이 아니라 종목별 토론 게시판(이벤트 선택적 태그)으로 시작. 매수·매도 권유는 키워드 1차+AI(Claude) 2차 하이브리드로 걸러내고, AI 미설정/실패 시 fail-closed(게시 차단)로 동작 — 컴플라이언스 게이트라 가용성보다 안전 우선. 신고 기능 포함(저장만, 처리 워크플로우는 범위 밖). unit 15/15, 전체 API 스위트 477/477. 남은 것: 실브로커리지 확장 없음(원래도 범위 밖), 신고 자동 숨김/관리자 대시보드, 투자자문업 법률 검토(human-action-items.md 반영 필요).
- [ ] 가상 투자 미션 / 친구 대결 리그

---

## 우선순위 제안

2026-09-09 기준 최우선: **§0 UI/UX 품질** — 상용배포 준비(§7 K8s 등)보다 먼저 진행하기로 사용자와 합의. 그중에서도 "홈 대시보드 연결"이 구현 비용 대비 임팩트가 가장 커서 첫 착수 대상.

그 다음, 의존관계 없이 바로 시작 가능한 순서:

1. ~~500/409 버그 수정~~ — ✅ 완료(2026-09-08, `485767e`)
2. ~~Netty `broadcast-gateway` 정리 결정~~ — ✅ 완료(2026-09-08, ADR-033)
3. ~~리밸런싱 실행 자동화~~ — ✅ 완료(2026-09-09, ADR-034, §3 실브로커리지/수동 실행분)
4. ~~Strategy Market 보안 구멍 수정~~ — ✅ 완료(2026-09-09, ADR-035). 결제 연동은 §5에 남음
5. ~~AI 자동매매 1라운드~~ — ✅ 완료(2026-09-09, ADR-036, §4 모의투자/수동 트리거분). 실브로커리지 확장·정기 스캔은 §4에 남음

KIS/Toss 관련 항목(§1의 다중 커넥션 풀링, §2의 OTO 이후 실서버 재검증)은 [human-action-items.md](human-action-items.md)의 플랫폼 키 발급이 먼저 끝나야 진행 가능 — 그 전까지는 순서상 뒤로 미룰 것.
