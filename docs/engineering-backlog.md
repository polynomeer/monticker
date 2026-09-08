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
- [ ] **[human-action-items.md](human-action-items.md)에서 이미 넘어온 500/409 버그 수정** — `GET /api/brokerage/account`가 신규 사용자에게 500 대신 409를 반환하도록 `GlobalExceptionHandler` 키워드 매칭 수정 (task_a9a6cb87로 이미 진행 중이면 중복 착수 금지, 완료 여부 먼저 확인).

## 3. 리밸런싱 실행 자동화

`PortfolioOptimizerService`(`backend/api/.../analytics/application/`)가 목표 비중 계산까지는 이미 한다. 없는 것:

- [ ] 현재 보유 비중 대비 목표 비중 **diff 계산** 로직
- [ ] diff가 **임계값을 초과한 종목만** 실행 대상으로 선별
- [ ] 선별된 diff를 기존 OMS/브로커 계층(`BrokerageService.submitOrder()`)으로 실제 주문 제출 — 조건부 주문(ADR-032)과 마찬가지로 **기존 리스크 게이트를 반드시 거치도록** 설계할 것(우회 금지 원칙은 ADR-032와 동일하게 적용)
- [ ] 실행 주기·트리거(수동 버튼 vs 스케줄) 설계 — ADR 필요(도메인 핵심 구조 채택)

## 4. AI 자동 매수/매도 (가드레일 필수)

`docs/product.md` 로드맵에 원칙만 명시돼 있고 구현은 없다:

- [ ] **Order Proposal 도메인 모델** — LLM이 생성하는 "주문 제안"을 실제 주문과 분리된 엔티티로 설계 (제안 ≠ 주문)
- [ ] **제안 → 리스크 검증 → 사용자 승인 → 기존 OMS 경유** 파이프라인 — 어떤 단계에서도 "승인 없이 자동 실행"으로 새는 경로가 없어야 함. ADR 필요(AI 연동 방식 결정에 해당).
- [ ] LLM 프롬프트 설계 — 기존 `StockSummaryService`의 "투자 조언 금지, 사실 기반만" 원칙을 주문 제안 생성에도 동일하게 적용
- [ ] 프론트: 제안 카드 UI + 승인/거부 인터랙션

## 5. Strategy Market 실 배포 마무리

룰셋 보호(서버사이드 실행, SHA-256 fingerprint)는 [ADR-023](decisions/023-commercialization-pivot.md)에서 이미 설계됨 — 실제 마켓 플로우(판매자 등록 → 구매자 구독 → 신호 전달)가 프로덕션 수준으로 끝까지 이어지는지 점검·마무리 필요. 어디까지 됐고 뭐가 빠졌는지부터 먼저 조사(Explore) 필요 — 이번 세션에서 확인 안 함.

## 6. QA/CI

- [ ] Phase 6 게이트: 백엔드/프론트 CI 통합테스트 스위트 전체 그린 유지 — 이번 세션 각 라운드마다 개별 확인했지만, 전체 스위트를 한 번에 돌리는 최종 확인은 별도로 필요
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

1. **500/409 버그 수정** (§2, 이미 배경 작업으로 시작됨) — 가장 작고 빠르게 끝남
2. ~~Netty `broadcast-gateway` 정리 결정~~ — ✅ 완료(2026-09-08, ADR-033)
3. **리밸런싱 실행 자동화** (§3) — 계산 로직은 이미 있어 상대적으로 작은 추가 작업
4. **Strategy Market 조사 + 마무리** (§5) — 조사부터 시작해 범위 확정
5. **AI 자동매매** (§4) — 가장 큰 신규 도메인, ADR부터

KIS/Toss 관련 항목(§1의 다중 커넥션 풀링, §2의 OTO 이후 실서버 재검증)은 [human-action-items.md](human-action-items.md)의 플랫폼 키 발급이 먼저 끝나야 진행 가능 — 그 전까지는 순서상 뒤로 미룰 것.
