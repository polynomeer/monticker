# ADR-049: quant-engine을 폐기한다 — 위임이 연결된 적 없는 두 번째 서비스, 격리는 bulkhead가 이미 한다

## Status
Accepted

## Context

[ADR-048](048-retire-trading-service.md)에서 trading-service가 "api가 한 번도 위임하지 않은 복사본"임을 확인하고
같은 의심을 `quant-engine`(Stage 3, :8082)에 적용했다.

### 확인된 사실

1. **`QuantEngineClient`도 어디에서도 호출되지 않는다.** `BacktestController`·`RuleSetController`·`AnalyticsController`는
   api 안의 로컬 서비스를 직접 부른다. nginx는 `/api/**` 전부를 api로 보낸다. `QUANT_ENGINE_URL`은 설정해도 아무 일도
   하지 않는다.
2. **quant-engine이 스스로 하는 일은 하나다** — `trading.order-filled`를 소비해 **로그 한 줄을 남기는**
   `OrderFilledKafkaConsumer`("향후 live tracking 추가 지점"). api에는 같은 이벤트를 프로세스 안에서 받는
   `OrderFilledStrategyListener`가 따로 있다. HTTP 트래픽 0, 실질 작업 0.
3. **복사본은 갈라졌다.** 51개 파일 중 12개가 api와 다르다(`BacktestController` 60줄, `AsyncConfig` 55줄,
   `RuleSetService` 44줄 …). [ADR-021](021-candles-1d-realtime-upsert.md)은 이미 이 포크본을 따로 대조·수정한 기록을
   남겼다. jsonb 매핑 결함(2026-09-14)도 두 번 고쳤다.
4. **분리의 명분 — "CPU-heavy 백테스트가 조회 경로를 방해한다" — 는 실측되지 않았다.** scale-out-plan §5는 분석 경로의
   격리를 요구하지만 "현재는 `backtestExecutor` bulkhead 하나만 이 원칙을 지킨다"고 적었다. 그 bulkhead가 충분한지를
   재기 위해 L-04를 만들었다([`bench/scenarios/backtest-bulkhead.js`](../../bench/scenarios/backtest-bulkhead.js)).

### L-04 실측 (2026-09-14, 로컬 10코어, 일봉 259일치)

조회 경로 4종(검색·스크리너·종목·캔들) 10 VU의 p95를, 백테스트 폭주 유무로 비교했다. 백테스트는
`backtestExecutor`(core 2 / max 4 / queue 20, 초과 시 429) 안에서 돈다.

| | 조회 p50 | 조회 p95 | 조회 p99 | 백테스트 | 429 |
|---|---|---|---|---|---|
| 대조군 (백테스트 없음) | 2.8ms | 7.7ms | 16.2ms | — | — |
| 폭주 20 VU | 3.2ms | 7.1ms | 12.1ms | 396/s, p95 77ms | 0 |
| 폭주 80 VU | 7.4ms | 29.4ms | 62.8ms | 310/s, p95 171ms | **11,596/s** |

20 VU 폭주는 조회 지연을 **전혀** 바꾸지 않았다. 80 VU에서 p95가 29ms로 오른 건 초당 1.2만 건의 429 응답을 Tomcat이
처리한 비용이지 백테스트 CPU가 아니다(bulkhead가 4 스레드로 묶는다) — 그래도 SLO(300ms)의 1/10이다. 분석 경로가
조회 경로를 방해한다는 실측 근거는 없다.

### 후보

- **A) 위임을 실제로 연결한다** — `QuantEngineClient`를 컨트롤러에 꽂고 복사본을 동기화한다. 두 벌 유지.
- **B) 폐기한다** — bulkhead가 격리를 맡는다. 격리가 실측으로 필요해지면 그때 api의 quant/analytics/backtest 모듈을
  Gradle 모듈로 뽑아 별도 배포 단위로 만든다(복사본이 아니라).
- **C) 유지** — 지금처럼.

## Decision

**B. 폐기한다.**

- `backend/quant-engine` 삭제. api의 `QuantEngineClient`·`quantEngine` 서킷브레이커·`quant.engine.url` 삭제.
- K8s `quant-engine.yaml`·`QUANT_ENGINE_URL`, compose 서비스, Makefile `quant-*`, dev.sh, CI 매트릭스·dependabot·
  Prometheus job에서 제거. compose `msa` 프로파일은 "역할 분리 워커 3종"만 남는다.
- `trading.order-filled` 토픽은 유지한다(api의 Modulith 외부화가 발행, 미래 소비자용). 소비자가 없어진
  `@RetryableTopic` 패밀리(`-retry-0..2`, `-dlt`)는 [ADR-040](040-kafka-topic-declaration.md)의 "선언은 컨슈머와 짝"
  원칙대로 선언에서 뺀다.
- scale-out-plan §5의 "분석 경로 격리" 요구는 유효하다 — 수단이 "복사본 서비스"가 아니라 "bulkhead, 그다음 모듈 추출"이다.

## Reasons

- **ADR-033 원칙**: 실측된 병목 없이 배포 단위를 두지 않는다. L-04가 그 실측이고 결과는 "병목 없음"이다.
- **A는 존재하지 않는 트래픽을 위해 51개 파일의 두 벌 유지를 약속하는 것**이다. ADR-021·jsonb 수정이 이미 그 비용을 두 번 냈다.
- **B는 잃는 게 없다.** HTTP 0, 유일한 컨슈머는 로그 한 줄, api가 같은 이벤트를 이미 프로세스 안에서 받는다.
- **"나중에 격리가 필요하면?"** — 그때 필요한 건 api의 모듈을 **공유 코드로** 뽑는 것이지 이 포크본이 아니다.
  포크본은 그 작업에 아무 도움이 안 된다(이미 갈라져 있으므로 오히려 방해다).

## Consequences

- **"MSA 모드"가 문서에서 사라진다.** Stage 3·5가 둘 다 폐기되므로 architecture.md의 Scaling Roadmap은 "모듈러 모놀리스
  + 역할 분리 워커 + Kafka"가 현 상태다. `QUANT_TRADING_EVENTS_ENABLED`도 사라진다.
- **`trading.order-filled`에 컨슈머가 없다.** 발행은 계속되고(Outbox), retention 30일 안에 소비자가 붙으면 된다.
  quant live-tracking을 실제로 만들 때는 api 안의 `OrderFilledStrategyListener`가 출발점이다.
- **bulkhead가 유일한 격리다.** `backtestExecutor` max 4는 pod CPU 요청(K8s `resources`)과 함께 봐야 한다 — 2 vCPU pod에서
  4 CPU 스레드는 Tomcat을 밀어낼 수 있다. L-04는 10코어 로컬 결과라 이 조합은 검증되지 않았다. 부하 환경이 생기면
  §5.1 시나리오에 L-04를 넣는다([ADR-045](045-performance-slo-and-verification-harness.md)).
- MongoDB(룰셋)는 api가 계속 쓴다 — quant-engine 전용 인프라가 아니었다.

## Revisit When

- **L-04를 prod 유사 환경(pod 리소스 제한)에서 돌렸을 때 조회 p95가 SLO를 넘으면** — 그때 quant/analytics/backtest를
  Gradle 모듈로 추출해 별도 배포 단위(전용 노드풀)로 만든다. scale-out-plan §5 "분석 경로" 행.
- **백테스트가 초 단위 이상 걸리는 데이터 규모(수년치 분봉)가 되면** — 동기 HTTP 응답이 아니라 작업 큐가 필요해지고,
  그 워커가 자연스러운 분리 단위다.
