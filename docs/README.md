# monticker 문서 색인

monticker의 모든 문서를 **독자별 · 주제별**로 정리한 색인입니다. 프로젝트 소개와 빠른 시작은 루트 [README.md](../README.md), 기여 방법은 [CONTRIBUTING.md](../CONTRIBUTING.md)를 보세요.

문서는 네 층으로 나뉩니다. 어떤 문서가 어디에 속하는지 헷갈리면 이 기준으로 찾으세요.

| 층 | 답하는 질문 | 위치 |
|----|-------------|------|
| **제품·기획** | 무엇을 왜 만드는가, 어디까지 됐는가 | `docs/*.md` (product, launch-plan …), [domain/](domain/README.md) |
| **설계·결정** | 어떤 구조로, 왜 그렇게 결정했는가 | [architecture.md](architecture.md), [data-model.md](data-model.md), [decisions/](decisions/) |
| **구현·운영** | 실제로 어떻게 만들었고 어떻게 굴리는가 | [technical/](technical/README.md), [runbooks/](runbooks/README.md), deployment |
| **사용·학습** | 화면을 어떻게 쓰는가, 용어가 무슨 뜻인가 | [manual/](manual/README.md), [stock-knowledge/](stock-knowledge/README.md) |

---

## 독자별 추천 경로

### 채용담당자 · 리뷰어 (10~30분)

1. [../README.md](../README.md) — 기능·아키텍처·엔지니어링 하이라이트
2. [portfolio.md](portfolio.md) — 문제 정의와 핵심 기술 결정 요약
3. [decisions/](decisions/) — ADR 50건. 특히 [023 상용화 전환](decisions/023-commercialization-pivot.md), [025 실주문 안전 게이트](decisions/025-real-brokerage-order-safety-gate.md), [036 AI 주문 제안](decisions/036-ai-order-proposal.md), [048](decisions/048-retire-trading-service.md)/[049](decisions/049-retire-quant-engine.md) MSA 폐기 결정
4. [technical/troubleshooting-casebook.md](technical/troubleshooting-casebook.md) — 실제로 겪은 문제 47건
5. [resilience-plan.md](resilience-plan.md), [security-review.md](security-review.md) — 스스로 찾아낸 결함과 대응

### 개발자 (처음 합류)

1. [../README.md 빠른 시작](../README.md#빠른-시작) → [../CONTRIBUTING.md](../CONTRIBUTING.md)
2. [architecture.md](architecture.md) — 모듈 경계, 파이프라인, API, 보안·복원력 설정
3. [data-model.md](data-model.md) — 전체 스키마, Redis 키 규칙
4. [workflow.md](workflow.md) — Claude Code 서브에이전트·훅·CI 워크플로
5. [domain/glossary-and-domain-knowledge.md](domain/glossary-and-domain-knowledge.md) — 증권/퀀트 용어가 낯설다면
6. 작업 영역에 맞는 [technical/](technical/README.md) 문서와 관련 ADR

### 일반 사용자

1. [manual/user-guide.md](manual/user-guide.md) — 화면별 사용법 전체
2. [stock-knowledge/](stock-knowledge/README.md) — 주식 기초부터 퀀트·한국 시장 제도까지 24장
3. [../README.md 면책 고지](../README.md#면책-고지) — 실전투자 기능을 쓰기 전 반드시

### 운영자 · SRE

1. [deployment.md](deployment.md) — OAuth·PG·브로커 등록, 프로덕션 환경변수
2. [platform-api-keys.md](platform-api-keys.md) — KIS/Toss 플랫폼 앱키 발급 절차
3. [runbooks/](runbooks/README.md) — 알람별 대응 절차 7종
4. [resilience-plan.md](resilience-plan.md) — 장애 시나리오·모니터링·부하/카오스 테스트
5. [scale-out-plan.md](scale-out-plan.md) — 대규모 트래픽 전환 로드맵

---

## 주제별 전체 목록

### 제품·계획

| 문서 | 내용 |
|------|------|
| [product.md](product.md) | 제품 정체성, 8개 기능 축, Quant Lab·Investment Wallet·Quant Analytics 개념, 상용화 로드맵 상태표, 핵심 설계 원칙 |
| [portfolio.md](portfolio.md) | 채용·리뷰용 기술 요약 — 문제 정의, 핵심 결정, 규모 |
| [launch-plan.md](launch-plan.md) | 상용 출시 체크리스트 — Phase 0(기술 부채) ~ 7(퍼블릭 출시), 각 Phase는 게이트 |
| [human-action-items.md](human-action-items.md) | 코드로 못 끝내는 일만 모은 목록 — 법무, 계정 발급, 사업자 등록 |
| [engineering-backlog.md](engineering-backlog.md) | 코드로 할 수 있는 남은 일 — 완료 항목은 근거 커밋과 함께 체크 |
| [legal-review-brief.md](legal-review-brief.md) | 변호사에게 전달할 브리핑 팩 — 사실관계 + 질문 목록 (법률 자문 아님) |
| [ui-benchmarks.md](ui-benchmarks.md) | 증권 앱 UI 벤치마크 (데스크톱 확장판은 `.docx`) |
| [screener.md](screener.md) | 스크리너 화면 설계 |
| [settlement.md](settlement.md) | 정산 시스템 설계 — 페이퍼/전략마켓/구독/증권사 4종, Mock→Real 전환 지점 |
| [event-storming.html](event-storming.html) | 이벤트 스토밍 결과 (브라우저로 열기) |

### 설계·결정

| 문서 | 내용 |
|------|------|
| [architecture.md](architecture.md) | 시스템 구조, 기술 스택, 모듈 경계, 워커 파이프라인, Quant Lab/체결엔진/리스크/지갑 아키텍처, API 목록, MSA 프로파일, 서킷브레이커·레이트리밋·DLT·멱등성·Outbox 설정 |
| [data-model.md](data-model.md) | PostgreSQL/TimescaleDB 전체 스키마, Redis 키 규칙 |
| [elasticsearch.md](elasticsearch.md) | ES 인덱스 6개·도메인 8개 적용 현황, 파이프라인, DB 폴백 |
| [external-apis.md](external-apis.md) | 시세·뉴스·공시·AI 외부 API 후보와 설정 |
| [decisions/](decisions/) | ADR 001~050. 형식과 작성 규칙은 [../CLAUDE.md](../CLAUDE.md#architecture-decision-records-adrs) |

**ADR 빠른 지도**

| 범위 | ADR |
|------|-----|
| 구조 | [001 모듈러 모놀리스](decisions/001-modular-monolith.md) · [019 Modulith 경계 규약](decisions/019-spring-modulith-boundary-conventions.md) · [009 K8s 서비스 디스커버리](decisions/009-kubernetes-service-discovery-nginx-gateway.md) · [048](decisions/048-retire-trading-service.md)/[049](decisions/049-retire-quant-engine.md) MSA 서비스 폐기 |
| 데이터 | [002 TimescaleDB](decisions/002-timescaledb.md) · [003 stock_events 중심](decisions/003-stock-events-central.md) · [021 일봉 실시간 upsert](decisions/021-candles-1d-realtime-upsert.md) · [041 hypertable 승격](decisions/041-timescale-hypertable-promotion.md) · [042 Outbox 기반 ES 색인](decisions/042-outbox-based-es-indexing.md) |
| 메시징·실시간 | [004 Redis Streams vs Kafka](decisions/004-redis-streams-over-kafka.md) · [005 Kafka + Go 게이트웨이](decisions/005-kafka-go-gateway-netty-broadcast.md) · [006 DLT 재시도](decisions/006-kafka-dlt-retry-strategy.md) · [008 Outbox](decisions/008-outbox-pattern-spring-modulith.md) · [029 시세 브로드캐스트](decisions/029-price-broadcast-pipeline.md) · [030 KIS](decisions/030-kis-realtime-tick-ingestion.md)/[031 Toss](decisions/031-toss-realtime-tick-ingestion.md) 실시간 체결가 · [033 Netty 게이트웨이 제거](decisions/033-remove-netty-broadcast-gateway.md) · [038 파티션 배정](decisions/038-broadcast-consumer-partition-assignment.md) · [039 전역 토픽 제거](decisions/039-drop-global-market-topic.md) · [040 토픽 선언](decisions/040-kafka-topic-declaration.md) · [050 부하테스트 기반 기본값](decisions/050-realtime-pipeline-defaults-from-load-tests.md) |
| 주문·자금 | [007 멱등성 키](decisions/007-idempotency-key-filter.md) · [011 주문 Saga](decisions/011-order-saga-orchestration.md) · [012 CQRS 포지션 읽기모델](decisions/012-cqrs-portfolio-positions-read-model.md) · [013 append-only 원장](decisions/013-append-only-ledger-wallet.md) · [014 T+2 정산](decisions/014-t2-paper-settlement-scheduler.md) · [043 원장 페이지네이션·대조](decisions/043-ledger-pagination-and-reconciliation.md) · [047 단일 체결 경로](decisions/047-single-execution-path-for-paper-account.md) |
| 실전투자(BYOK) | [015 Mock/Real 클라이언트](decisions/015-conditional-mock-real-client.md) · [017 투자자 동향 KIS](decisions/017-investor-flow-kis-integration.md) · [018 펀더멘털 KIS 재사용](decisions/018-stock-fundamentals-kis-reuse.md) · [023 상용화 전환](decisions/023-commercialization-pivot.md) · [025 안전 게이트](decisions/025-real-brokerage-order-safety-gate.md) · [026 Toss 연동](decisions/026-toss-brokerage-integration.md) · [027 자격증명 갱신](decisions/027-brokerage-credential-refresh.md) · [028 주문 취소](decisions/028-brokerage-order-cancellation.md) · [032 조건부 주문](decisions/032-conditional-orders.md) · [034 리밸런싱 실행](decisions/034-rebalancing-execution.md) |
| Quant Lab·AI·커뮤니티 | [016 구독·제작자 수익](decisions/016-subscription-creator-revenue-sharing.md) · [020 밸류에이션 스코어](decisions/020-stock-valuation-score.md) · [024 포워드 테스트](decisions/024-quant-lab-forward-test.md) · [035 신호 접근 제어](decisions/035-strategy-market-signal-access-control.md) · [036 AI 주문 제안](decisions/036-ai-order-proposal.md) · [037 종목 커뮤니티](decisions/037-stock-community-comments.md) |
| 탐지·성능 | [010 Bloom Filter 뉴스 중복 제거](decisions/010-bloom-filter-news-deduplication.md) · [022 틱 컨슈머 역할 게이팅](decisions/022-tick-consumer-msa-role-gating.md) · [044 알림 룰 인메모리 인덱스](decisions/044-alert-rule-in-memory-index.md) · [045 성능 SLO·검증 하네스](decisions/045-performance-slo-and-verification-harness.md) · [046 탐지기 인메모리 상태](decisions/046-detector-state-in-memory.md) |

### 구현·운영

| 문서 | 내용 |
|------|------|
| [technical/README.md](technical/README.md) | 구현 심층 문서 32편 — 데이터 파이프라인, 실시간, 보안, 금융 도메인, 성능, 아키텍처 패턴, 복원력, 트러블슈팅 |
| [runbooks/README.md](runbooks/README.md) | 알람 ↔ 런북 매핑 7종 (Redis 다운, 원장 불일치, 브로커 CB, 틱 정지, DB failover, 롤백, 검색 인덱스) |
| [deployment.md](deployment.md) | 프로덕션 배포 — OAuth2 앱 등록, 토스페이먼츠, 환경변수 체크리스트 |
| [platform-api-keys.md](platform-api-keys.md) | KIS/Toss 플랫폼 앱키 발급 절차 (실명·사업자 인증 필요) |
| [resilience-plan.md](resilience-plan.md) | 장애 시나리오별 대응 능력 판정, 모니터링·부하·카오스 테스트 설계 |
| [security-review.md](security-review.md) | 시큐어 코딩/보안 설계 점검 — Critical 3건과 우선순위별 개선안 |
| [validation-hardening-plan.md](validation-hardening-plan.md) | 입력값·논리 분기 검증 점검 (완료, PR #80) |
| [scale-out-plan.md](scale-out-plan.md) | 병목 인벤토리, 목표 아키텍처, Phase 0~4 전환 로드맵 |
| [workflow.md](workflow.md) | Claude Code 개발 워크플로 — 서브에이전트, 훅, CI/CD |

### 사용·학습

| 문서 | 내용 |
|------|------|
| [manual/user-guide.md](manual/user-guide.md) | 화면별 사용 설명서 — 스크리너, 종목 상세, 관심종목, 알림, 모의투자, 체결엔진, 리스크, 지갑, 포트폴리오, 백테스팅, Quant Lab, Analytics, 실전투자, 구독, 설정, FAQ |
| [domain/README.md](domain/README.md) | 제품·비즈니스 판단의 근거 5편 — 용어집, Quant Lab 포지셔닝, 지갑 UX 철학, 리스크 관리 신뢰, Analytics 사용자 가치 |
| [stock-knowledge/README.md](stock-knowledge/README.md) | 주식 도메인 백과 24장 — 시장 구조, 차트 분석, 포트폴리오·리스크, 퀀트·백테스트, 한국 시장 제도, monticker 도메인 매핑 |

---

## 문서 관리 규칙

- **설계 결정을 바꾸면 새 ADR** — 기존 ADR은 `Superseded by ADR-NNN`으로 상태만 바꾸고 내용은 남깁니다. 규칙 전문은 [../CLAUDE.md](../CLAUDE.md#architecture-decision-records-adrs).
- **완료된 작업은 체크리스트 문서를 함께 갱신** — `launch-plan.md`, `engineering-backlog.md`, `human-action-items.md`는 살아있는 문서입니다. PR에서 관련 항목을 끝내면 근거 커밋/PR 번호와 날짜를 함께 적습니다.
- **product.md 로드맵 상태표는 코드 상태와 일치해야 합니다** — 기능을 완료하면 해당 행을 ✅로 바꾸고 ADR·문서 링크를 답니다.
- **technical/ 는 "어떻게", domain/ 은 "왜"** — 구현 세부를 domain에, 비즈니스 근거를 technical에 섞지 않습니다.
- **manual/ 은 사용자 언어로** — 클래스명·테이블명·ADR 번호를 쓰지 않습니다. 개발자 참고가 필요하면 문서 끝 "더 알아보기"에만 링크합니다.
- 문서 갱신은 `docs:` 스코프의 별도 커밋으로 분리합니다 ([../CLAUDE.md 커밋 컨벤션](../CLAUDE.md#commit-convention)).
