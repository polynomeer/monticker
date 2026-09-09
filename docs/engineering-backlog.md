# monticker — 상용화까지 코드로 구현 가능한 작업 목록

> Read this when: 다음에 뭘 구현할지 고를 때. 사람이 직접 해야 하는 일(계정/키 발급, 법률 자문 등)은 [human-action-items.md](human-action-items.md) 참고 — 이 문서는 코드/설계로 끝낼 수 있는 것만 담는다.

각 항목은 "왜 남았는지"와 "어디서 이어가는지"를 같이 적었다. 대부분 기존 ADR의 "Revisit When"에서 가져왔다 — 그 ADR을 먼저 읽고 시작할 것.

---

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
- [ ] **실브로커리지로 확장** — `BrokerageService.submitOrder()`도 같은 "승인 → 프론트가 별도 제출" 패턴을 따를지, 서버 사이드 직접 호출(브로커리지 모듈은 matching과 달리 이벤트 전용 제약이 없음)로 갈지 재검토 필요. [ADR-036 Revisit When](decisions/036-ai-order-proposal.md#revisit-when)
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

  별개로 발견(범위 밖, 미해결): `docker build --target builder`가 `Cannot find module '@monticker/types'`로 실패한다 — `apps/web/Dockerfile`의 `deps` 스테이지가 `--filter @monticker/web...`으로 `packages/types`까지 설치하지만 그 워크스페이스 심볼릭 링크가 `builder` 스테이지에서 `packages/types` 소스를 COPY한 뒤에도 깨져 있는 것으로 보임. `deploy-images.yml`은 PR이 아니라 `main` push에서만 도는 워크플로우라 이번 PR 머지를 막지는 않지만, 다음 `main` push 때 이미지 빌드가 이 문제로 실패할 것 — 별도로 고쳐야 함.
- [x] **위 Suspense 수정(PR #63)이 실제 GitHub Actions에서 한 번도 검증되지 않았음을 발견 + `next` 보안 패치** — ✅ next 패치 완료(2026-09-09), audit 게이트 자체는 여전히 미해결(아래 참고). PR #63의 실제 CI 런 3회를 확인한 결과 매번 `Install dependencies` 다음의 `pnpm audit --audit-level=high` 스텝에서 먼저 실패해 `Lint`/`Test`/`Build`가 전부 skip됐다 — 즉 위 Suspense 타입 수정은 로컬 검증만 있었을 뿐 실제 CI의 `next build`로는 한 번도 확인된 적이 없었다. 게다가 PR #63은 CI가 계속 빨간불인 채로 이미 main에 머지됐다. 원인은 `apps/web/package.json`이 `next`를 캐럿 없이 `15.1.0`으로 정확히 고정해뒀던 것 — 이 버전에 RCE(React flight protocol, Windows 호스트, AVIF Image Optimization), 인증 우회(Middleware), 다수의 SSRF/DoS 등 critical 5건을 포함해 75건의 알려진 취약점이 있었다(`web-ci.yml`은 `apps/web/**` 변경 시에만 트리거되지만 audit 자체는 워크스페이스 전체를 스캔하므로, 2026-09-07 이후 이 저장소를 거친 web 관련 PR이 거의 전부 이 지점에서 막혀 있었다). `next`/`eslint-config-next`를 `15.5.25`로 올리고(같은 메이저 내 최신 안정판), 루트 `package.json`의 `packageExtensions` 키를 `next@15.5.25`로 갱신, `next`가 번들한 `postcss@8.4.31`(자체 취약점 4건)도 `overrides`로 `next>postcss: >=8.5.23`으로 강제 — 검증: `pnpm audit`에서 `next` 관련 항목 0건, `pnpm --filter web exec tsc --noEmit` 클린(Suspense 포함), `pnpm --filter web build` 클린(모든 라우트 정상 생성), `pnpm --filter web lint`/`test` 클린(37/37), `pnpm install --frozen-lockfile` 클린, `docker build --target deps --frozen-lockfile` 클린, `pnpm --filter mobile exec tsc --noEmit` 무영향 확인.

  **미해결로 남긴 부분**: `pnpm audit --audit-level=high`는 pnpm v9에서 워크스페이스 단위로만 동작하고 `--filter`를 지원하지 않아, `apps/web` PR이어도 `apps/mobile`의 취약점까지 전부 게이트에 걸린다. 이번 패치 후에도 여전히 critical 1건 + high 25건이 남는데, 전부 `apps/mobile > expo@52.0.49 > @expo/cli...` 전이 의존성(`@xmldom/xmldom`, `tar`, `image-size`, `uuid`, `decode-uri-component`, mobile 쪽 `postcss@8.4.49`)이거나, `apps/web`이 직접 쓰는 `echarts@5.6.0`의 XSS(패치는 `>=6.1.0` — 메이저 업그레이드라 차트 렌더링 회귀 테스트 없이는 이번 라운드에 포함하지 않음)다. 즉 **`web-ci.yml`의 audit 스텝은 이 두 가지(mobile 전이 의존성, echarts 메이저 업)를 해결하기 전까지는 구조적으로 계속 실패한다** — 다음 중 하나를 정책적으로 결정해야 한다: (1) mobile의 expo 관련 전이 의존성을 별도 라운드로 패치(버전 강제가 cacache/expo-cli 내부 동작을 깨뜨릴 위험이 있어 mobile e2e/빌드 검증 필요), (2) ~~`echarts`를 6.x로 올리고 모든 차트 컴포넌트 시각 회귀 테스트~~ → 완료, 아래 참고, (3) audit 스텝을 워크스페이스 단위가 아니라 PR이 실제로 건드리는 앱 범위로 한정하도록 CI 자체를 재설계(pnpm v9는 `audit --filter`가 없어 스크립트로 JSON 결과를 경로 기준 필터링해야 함).
- [x] **`echarts@5.6.0`의 XSS 취약점(위 항목에서 미룬 부분) 패치** — ✅ 완료(2026-09-09). `apps/web`이 echarts를 쓰는 5개 파일(`EChartsAdapter`/`VolumeChart`/`IndicatorChart`/`BacktestResultView`/`quant-lab/[id]`) 전부 `import("echarts")`로 표준 `echarts.init`/`setOption` API만 쓰고, 색상·범례 위치·툴팁 스타일을 커스텀 `theme` 객체로 명시 지정해 echarts 6.0의 breaking change(기본 테마 변경, 범례 기본 위치 하단 이동, 축 anti-overflow 기본 활성화)에 해당하는 "기본값 의존" 코드가 없음을 소스 확인. `echarts`를 `^5.5.0` → `^6.1.0`으로 올림 — 검증: `tsc --noEmit`/`build`/unit test(37/37) 클린, `pnpm audit`에서 echarts 항목 0건. 이 5개 컴포넌트에 자동 테스트가 전혀 없어(스냅샷·유닛 전무) 백엔드 전체 스택 대신 필요한 API(`/api/stocks/search`, `/candles`, `/orderbook` 등)만 흉내 낸 목 서버로 실데이터 형태를 흘려 브라우저에서 직접 확인: 캔들/거래량/RSI/MACD(범례 포함) 차트가 다크·라이트 테마 모두에서 정상 렌더링, echarts 관련 콘솔 에러 없음. `/backtest`(`BacktestResultView`)는 백엔드 POST 응답 스펙이 복잡해 실행까지는 안 해봤으나 동일한 `echarts.init`+bar/line 렌더링 경로를 다른 화면에서 이미 확인했으므로 리스크는 낮음.
- [ ] `scripts/load-test/k6-smoke.js` 부하테스트 시나리오 확장 — 지금은 스크리너/로그인/모의투자 주문 3종만. 실브로커 주문(BYOK) 경로, 조건부 주문 발동 경로는 아직 부하테스트 시나리오에 없음

## 7. 인프라 코드 작업 (프로비저닝 자체는 human-action-items.md, 코드/스크립트는 여기)

- [ ] **실 K8s 클러스터 배포 파이프라인** — 이미지는 GHCR까지 자동 푸시됨([.github/workflows/deploy-images.yml](../.github/workflows/deploy-images.yml)), 그 이미지를 실제 클러스터에 적용하는 CD 스텝(`kubectl apply`/ArgoCD/Flux 등)이 없음 — 클러스터가 실제로 생기면 이어서 작성
- [ ] **PITR(WAL 아카이빙) 스크립트** — 백업/복구(`infra/db/`)는 로컬에서 리허설 완료, WAL 아카이빙은 실 프로덕션 Postgres가 있어야 리허설 가능 — 스크립트 자체는 미리 작성해둘 수 있음

## 8. 우선순위 낮음 — 스펙부터 필요

아래는 "작업 단위"로 쪼갤 수 있을 만큼 구체적인 설계가 아직 없다. 착수 전 제품 스펙 논의(도메인 모델, 화면 흐름)부터 필요:

- [ ] 소셜 커뮤니티
- [ ] 가상 투자 미션 / 친구 대결 리그

---

## 우선순위 제안

의존관계 없이 바로 시작 가능한 순서:

1. ~~500/409 버그 수정~~ — ✅ 완료(2026-09-08, `485767e`)
2. ~~Netty `broadcast-gateway` 정리 결정~~ — ✅ 완료(2026-09-08, ADR-033)
3. ~~리밸런싱 실행 자동화~~ — ✅ 완료(2026-09-09, ADR-034, §3 실브로커리지/수동 실행분)
4. ~~Strategy Market 보안 구멍 수정~~ — ✅ 완료(2026-09-09, ADR-035). 결제 연동은 §5에 남음
5. ~~AI 자동매매 1라운드~~ — ✅ 완료(2026-09-09, ADR-036, §4 모의투자/수동 트리거분). 실브로커리지 확장·정기 스캔은 §4에 남음

KIS/Toss 관련 항목(§1의 다중 커넥션 풀링, §2의 OTO 이후 실서버 재검증)은 [human-action-items.md](human-action-items.md)의 플랫폼 키 발급이 먼저 끝나야 진행 가능 — 그 전까지는 순서상 뒤로 미룰 것.
