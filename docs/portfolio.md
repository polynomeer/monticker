# monticker — Portfolio Write-up

> Read this when: 채용담당자·리뷰어에게 프로젝트를 설명하거나, 기술 면접 전에 핵심 결정을 다시 정리할 때. 사실관계는 [README.md](../README.md)·[architecture.md](architecture.md)·[decisions/](decisions/)와 동기화되어야 한다.

## 한 줄 요약

주가가 **왜** 움직였는지를 이벤트 타임라인으로 보여주고, 코딩 없이 만든 투자 규칙을 백테스트·포워드 테스트로 검증하며, 돈의 이동을 원장 기반으로 추적하는 이벤트 중심 주식 관찰 플랫폼. 모의투자에서 출발해 사용자 본인 명의 증권사 계좌(BYOK)로 실주문까지 확장했다.

- **기간**: 2026-01 ~ (진행 중, MVP 완료 → 상용화 단계)
- **규모**: 커밋 700+ · ADR 50건 · Flyway 마이그레이션 47개 · 백엔드 모듈 26개 · 웹 라우트 37개 · 테스트 580+ (api 469 · worker 80 · web 37, 2026-09-09 기준)
- **스택**: Kotlin 2.0 / Spring Boot 3.5 / Spring Modulith · Next.js 15 / React 19 · Expo 52 · Go 1.22 · PostgreSQL 16 + TimescaleDB · Redis 7 · MongoDB 7 · Elasticsearch · Kafka · Docker Compose / Kubernetes · Prometheus / Grafana / Jaeger / Pinpoint
- **개발 방식**: 1인 개발 + Claude Code(서브에이전트 6종, 자동 리뷰 워크플로). 주차별 수직 슬라이스로 항상 동작하는 상태 유지.

## 문제 정의

기존 주식 앱은 "현재가"를 나열한다. 개인 투자자가 실제로 필요한 것은 **"지금 무슨 일이 일어나고 있는가"**와 **"내 판단이 맞았는지 어떻게 검증하는가"**다. monticker는 세 가지로 답한다:

1. **이벤트 타임라인** — 가격·거래량 이상을 자동 탐지해 뉴스·공시·차트 패턴과 함께 차트 위에 겹친다.
2. **Quant Lab** — 아이디어를 규칙으로 만들고, 편향을 통제한 백테스트와 실시간 포워드 테스트로 검증한다.
3. **Investment Wallet** — 잔고 숫자가 아니라 "내 돈이 어디에 어떤 상태로 있는지"를 원장 이벤트로 보여주고, 주문 시점 감정과 결과를 연결해 복기하게 한다.

## 핵심 기술 결정 (요약)

각 항목은 ADR 또는 기술 문서에 근거 실측과 대안 비교가 있다.

### 1. 이벤트 중심 도메인 — `stock_events`가 중심, 가격은 부산물
`stock_events`를 중심 객체로 두고 모든 이상 징후를 이벤트로 정규화했다. EMA(α=0.1) 적응형 임계값으로 시장 변동성에 따라 기준이 움직이고, `(stock_id, event_type, minute)` 유니크 인덱스로 중복을 DB 레벨에서 막는다. → [ADR-003](decisions/003-stock-events-central.md), [ema-event-detection.md](technical/ema-event-detection.md)

### 2. 모듈러 모놀리스, 그리고 "MSA로 갔다가 돌아온" 결정
Spring Modulith로 26개 모듈의 경계를 테스트로 강제한다. quant-engine·trading-service를 별도 서비스로 추출했지만, 위임 코드가 한 번도 연결되지 않아 4개월간 트래픽 0이었고 부하 실측에서 in-process bulkhead로 충분함이 확인돼 **폐기했다**. 실패한 결정도 ADR로 남긴 것이 이 프로젝트의 문서 문화다. → [ADR-001](decisions/001-modular-monolith.md), [ADR-048](decisions/048-retire-trading-service.md), [ADR-049](decisions/049-retire-quant-engine.md)

### 3. 돈이 움직이는 경로의 정합성 — Saga + Outbox + 원자적 예약 + 멱등성
주문은 `RiskChecker(5규칙) → reserveCash(원자적 UPDATE … WHERE cash >= ?) → CLOB 매칭 → 원장 기록 → @Externalized 이벤트(Outbox) → Kafka` 순서다. 보상 트랜잭션과 5분 복구 스케줄러, `X-Idempotency-Key`로 네트워크 재시도 중복을 막는다. Testcontainers 10스레드 동시성 테스트와 Kafka 브로커 다운 카오스 테스트로 검증했다. → [ADR-011](decisions/011-order-saga-orchestration.md), [ADR-008](decisions/008-outbox-pattern-spring-modulith.md), [ADR-007](decisions/007-idempotency-key-filter.md)

### 4. 이벤트 소싱 원장 — 잔고 컬럼이 없다
`ledger_events`는 append-only이고 잔고는 replay 합산이다. 스냅샷 기반 페이지네이션으로 읽기 비용을 잡고, 야간 대조 작업이 불일치를 찾으면 **자동 교정하지 않고** 알람 + 런북으로 사람에게 넘긴다. → [ADR-013](decisions/013-append-only-ledger-wallet.md), [ADR-043](decisions/043-ledger-pagination-and-reconciliation.md), [ledger-mismatch.md](runbooks/ledger-mismatch.md)

### 5. CLOB 체결엔진과 사전 리스크 게이트
모의투자지만 실제 거래소 규칙(가격·시간 우선, 부분 체결, 다단 슬리피지)을 TreeMap 호가 큐로 구현했다. 리스크 한도(일일손실·집중도·VaR·종목수·빈도)는 주문 **전** 동기 게이트라 한도 초과 주문은 엔진에 도달하지 않는다. 실주문도 같은 게이트를 강제하고, 브로커 네이티브 조건주문은 게이트 우회 위험으로 쓰지 않는다. → [matching-engine-clob.md](technical/matching-engine-clob.md), [risk-limit-system.md](technical/risk-limit-system.md), [ADR-025](decisions/025-real-brokerage-order-safety-gate.md)

### 6. BYOK 실주문 — 브로커가 아니라 클라이언트
자체 라이선스 없이 사용자 본인의 KIS/Toss Open API 키로 대신 주문한다. 브로커 무관 인터페이스 + KIS/Toss/Mock 구현, 프로바이더별 서킷브레이커, AES-256-GCM 자격증명 암호화, 토큰 자동 재발급, 취소의 실제 전달, 조건부 주문(OCO)과 리밸런싱까지 구현했다. 코드는 완료됐고 실계좌 라이브 검증과 법무 자문은 사람의 액션으로 대기 중이다. → [ADR-023](decisions/023-commercialization-pivot.md), [ADR-026](decisions/026-toss-brokerage-integration.md), [ADR-032](decisions/032-conditional-orders.md), [ADR-034](decisions/034-rebalancing-execution.md)

### 7. AI 가드레일 — 제안은 하되 실행은 못 한다
LLM은 방향(BUY/SELL/HOLD)과 근거만 생성하고 수량·가격을 정하지 않는다. "승인"은 제안 상태만 바꾸며 실제 제출은 사용자가 리스크 게이트가 있는 주문 폼에서 직접 눌러야 한다 — "승인 즉시 자동 체결"로 새는 경로가 구조적으로 없다. 커뮤니티 댓글의 매수·매도 권유는 키워드+AI 하이브리드로 걸러내되 판정 실패 시 게시를 막는 fail-closed다. → [ADR-036](decisions/036-ai-order-proposal.md), [ADR-037](decisions/037-stock-community-comments.md)

### 8. 실시간 파이프라인 — 부하 테스트로 기본값을 정했다
`market.ticks`는 stockId 파티션 키, 브로드캐스트 컨슈머는 파티션 고정 배정, 전역 `/topic/market`은 스케일아웃을 막아 제거했다. 파티션 수·컨슈머 동시성·세션 타임아웃 기본값은 k6 부하 테스트 결과로 산출했다. TimescaleDB hypertable + continuous aggregate로 캔들을 자동 집계한다. → [ADR-029](decisions/029-price-broadcast-pipeline.md), [ADR-038](decisions/038-broadcast-consumer-partition-assignment.md), [ADR-039](decisions/039-drop-global-market-topic.md), [ADR-050](decisions/050-realtime-pipeline-defaults-from-load-tests.md)

### 9. 룰셋 보호 — 서버사이드 평가, 클라이언트 비노출
전략 마켓의 룰셋은 절대 클라이언트로 내려가지 않는다. 서버가 평가한 신호만 전달하고, STOMP 구독 인가로 소유자·유료 구독자만 신호 토픽을 받는다(감사 중 발견한 인가 누락을 수정). SHA-256 fingerprint로 변조를 탐지한다. → [ADR-035](decisions/035-strategy-market-signal-access-control.md), [quant-lab-positioning.md](domain/quant-lab-positioning.md)

### 10. 복원력과 운영 — 알람 하나에 런북 하나
외부 호출마다 Resilience4j CB + 로컬 폴백, Kafka DLT, 백테스트 bulkhead, 분산 락, graceful shutdown. Redis는 레이트리밋·캐시는 fail-open, 멱등성은 fail-closed로 구분했다. Prometheus 알람마다 대응 런북(7종)이 있고, pg_dump CronJob과 복구 리허설(로컬 PASS, 캔들 217,051행 일치)이 있다. 장애 시나리오별 대응 능력을 스스로 판정해 P0 결함 7건(Redis 실패 정책, HTTP 타임아웃, 알람 채널, 백업, readiness, PDB 등)을 2026-09-11에 전부 해결했다. → [resilience-plan.md](resilience-plan.md), [runbooks/](runbooks/README.md), [resilience-patterns.md](technical/resilience-patterns.md)

### 11. 보안 — 스스로 찾아낸 Critical 3건
보안 점검에서 하드코딩 시크릿의 배포 경로 공백, 토큰 localStorage 저장, 실브로커 주문 입력검증 부재를 Critical로 분류하고 해결했다. git에 커밋된 개발용 키로는 API가 기동을 거부한다(`InsecureSecretGuard`). JWT 15분 + refresh 7일 로테이션, 2-tier 레이트리밋(IP·userId). → [security-review.md](security-review.md), [validation-hardening-plan.md](validation-hardening-plan.md), [jwt-authentication.md](technical/jwt-authentication.md)

## 아키텍처 요약

```
외부 데이터 (KIS/Toss 실시간체결가 · 네이버 뉴스 · DART 공시 · Yahoo Finance)
  → backend/worker  (시세 수집 → Kafka market.ticks → 캔들 집계 · EMA 이벤트 탐지 · 알림 평가 · 뉴스/공시 수집)
  → TimescaleDB (ticks/candles hypertable, stock_events, ledger_events) · Redis · MongoDB · Elasticsearch
  → backend/api     (Spring Modulith 26모듈 — 인증 · 시세 · 이벤트 · 모의투자 · CLOB 체결 · 리스크 · 지갑 ·
                     Quant Lab · Analytics · 실전투자(BYOK) · 구독 · 정산 · 커뮤니티 · PriceBroadcaster STOMP)
  → apps/web (Next.js 15) · apps/mobile (Expo)
```

상세 다이어그램: [README.md 아키텍처](../README.md#아키텍처-한눈에-보기), [architecture.md](architecture.md)

## 문서 문화

이 프로젝트에서 코드만큼 공들인 것이 문서다. 결정은 ADR(50건)로, 구현은 기술 문서(32편)로, 문제는 트러블슈팅 사례집(47건)으로, 사용법은 매뉴얼로, 장애 대응은 런북으로 남긴다. 결정을 번복할 때는 기존 ADR을 고치지 않고 새 ADR로 Supersede한다. 색인: [docs/README.md](README.md)

## 현재 상태와 남은 것

| 항목 | 상태 |
|------|------|
| 8개 기능 축 + BYOK 실주문 + AI 제안 + 커뮤니티 | ✅ 구현 완료 |
| 상용화 선행 기술 부채 (동시성·CB·암호화) | ✅ 2026-09-05 완료 |
| 보안·입력검증 강화 | ✅ PR #80 |
| 복원력 설계·모니터링·런북·백업 | ✅ 1차 완료 |
| 실시간 시세 실데이터·실계좌 라이브 검증 | 🟡 플랫폼 앱키 발급(실명 인증) 대기 |
| 법무 자문 (자본시장법·유사투자자문업·전자금융거래법) | ⏳ [legal-review-brief.md](legal-review-brief.md) 전달 대기 |
| 결제(토스페이먼츠) 실연동 | ⏳ 스텁 |
| 대규모 트래픽 전환 | 📋 [scale-out-plan.md](scale-out-plan.md) Phase 0~4 계획 |

## 더 읽기

- [README.md](../README.md) — 기능·아키텍처·빠른 시작·엔지니어링 하이라이트
- [decisions/](decisions/) — 결정의 이유
- [technical/troubleshooting-casebook.md](technical/troubleshooting-casebook.md) — 실제로 겪은 문제 47건
- [product.md](product.md) — 제품 정체성과 로드맵 상태표
