# ADR-048: trading-service를 폐기한다 — 한 번도 트래픽을 받은 적이 없는 복사본

## Status
Accepted

## Context

`backend/trading-service`는 Stage 5([architecture.md](../architecture.md) "Scaling Roadmap")에서 paper·matching·wallet
모듈을 별도 배포 단위로 뽑아낸 것이다(`fc31148`, 2026-07-08). 설계 의도는 "api가 `TRADING_SERVICE_URL`이 설정되면
요청을 위임한다"였고, 그렇게 문서화됐다(architecture.md §Gateway, [ADR-009](009-kubernetes-service-discovery-nginx-gateway.md),
K8s ConfigMap의 `TRADING_SERVICE_URL`, Makefile `msa` 타깃).

### 확인된 사실

1. **api는 한 번도 위임한 적이 없다.** `TradingServiceClient`는 추출 커밋에서 만들어졌지만 **어디에서도 호출되지 않는다** —
   `MatchingController`·`PaperController`·`WalletController`는 추출 커밋 그 시점부터 로컬 서비스를 직접 불렀다.
   nginx 게이트웨이도 `/api/**` 전부를 api로 보낸다. `TRADING_SERVICE_URL`은 설정해도 아무 일도 하지 않는 값이다.
2. **따라서 K8s의 trading-service Deployment는 트래픽을 0건 받는다.** 스케줄러도 Kafka 컨슈머도 없어 스스로 하는 일도
   없다. 하는 것은 공유 DB에 커넥션 풀 20개를 잡고, 이미지를 빌드·배포·스캔(dependabot 2건)시키는 것뿐이다.
3. **복사본은 이미 갈라졌다.** 63개 파일이 api의 paper/matching/wallet을 복제한 것인데 [ADR-043](043-ledger-pagination-and-reconciliation.md)이
   "두 곳을 함께 고쳐야 한다"며 원장 변경을 두 번 했고, [ADR-047](047-single-execution-path-for-paper-account.md)은
   `PaperExecutionListener`·매도 보유 확인이 복사본에 없다고 남겼다. `WalletService.calcHoldingsValue`는 두 구현이
   아예 다른 SQL이다(api는 `portfolio_positions` 조인, 복사본은 `paper_trades` 순회). 두 번 연속 발목을 잡았다.
4. **`trading.order-filled`의 발행자라는 역할도 실제로는 api가 한다.** 문서는 trading-service의
   `OrderEventKafkaPublisher`를 발행자로 적었지만 체결이 그쪽에서 일어난 적이 없다. api의 Modulith 외부화가
   (CH-05에서 고친 뒤) 같은 토픽에 발행하고 quant-engine이 그걸 소비한다.
5. **resilience-plan E6의 "수정"은 잘못된 곳을 고쳤다.** "trading-service replicas 2 → 1 (호가창이 힙에 있으므로)"은
   트래픽이 없는 서비스의 replica를 줄인 것이다. 호가창이 힙에 있는 프로세스는 **api**이고 api는 HPA로 최대 6 pod다.
   다만 오늘은 문제가 되지 않는다 — `OrderSagaOrchestrator`가 `orderBookService.submit()`의 매칭 결과를 버리므로
   호가창은 미체결 LIMIT을 **보관만 하고 체결시키지 않는다**(ADR-043 검증 중 확인). 호가창이 pod마다 갈라져 있어도
   갈라진 호가창끼리 체결될 일이 없다. 진짜 단일 라이터 문제는 scale-out-plan §6.8(샤딩)이 다루며, 이 ADR과 무관하게
   api에 이미 존재한다.

### 후보

- **A) 복사본을 동기화하고 위임을 실제로 연결한다** — `TradingServiceClient`를 컨트롤러에 꽂고, ADR-043·047 변경을
  복사본에 넣고, 앞으로도 두 벌을 유지한다.
- **B) 폐기한다** — 모듈·이미지·매니페스트·설정·문서를 제거한다. matching은 api 안의 모듈로 남는다(지금도 그렇다).
- **C) 공유 라이브러리로 뽑는다** — paper/matching/wallet을 별도 Gradle 모듈로 만들어 api와 trading-service가 같은
  코드를 쓴다.

## Decision

**B. 폐기한다.**

- `backend/trading-service` 삭제. api의 `TradingServiceClient`·`tradingService` 서킷브레이커·`trading.service.url` 삭제.
- K8s `trading-service.yaml`·`TRADING_SERVICE_URL`, compose `trading-service`, Makefile `trading-*`/`msa`의 trading 부분,
  CI 매트릭스·dependabot·Prometheus job·`ServiceDown` 알람 표현식에서 제거.
- 문서: architecture.md Stage 5는 "추출했으나 위임이 연결된 적 없음 → 폐기(ADR-048)"로. scale-out-plan §3.7·§3.8,
  resilience-plan E6, ADR-009·043·047의 trading-service 언급을 정정.
- `quant-engine`의 `trading.order-filled` 소비는 그대로 — 발행자는 api다.

## Reasons

- **A는 존재하지 않는 요구를 위해 비용을 두 배로 만든다.** 별도 배포가 필요한 이유(독립 스케일링, 장애 격리)는
  [ADR-033](033-remove-netty-broadcast-gateway.md)의 원칙대로 실측된 병목이 있어야 정당화된다. 4개월 동안 아무도 위임이
  안 된 걸 눈치채지 못했다는 사실 자체가 "이 분리는 아무것도 해결하고 있지 않았다"는 증거다.
- **C는 A의 유지비를 줄이지만 B가 없애는 비용은 못 없앤다** — 이미지·매니페스트·풀 20개·CI 시간. 그리고 실제로 두
  프로세스가 필요해질 때(§6.8 샤딩) 필요한 건 "같은 코드 두 벌"이 아니라 "종목 샤드별 단일 라이터"라 C의 형태도 아니다.
- **폐기해도 잃는 기능이 없다.** 트래픽 0, 스케줄러 0, 컨슈머 0. 유일한 발행자 역할도 api가 이미 하고 있다.

## Consequences

- **모놀리스 배포 단위가 하나 줄고, "MSA 모드"는 quant-engine 위임만 남는다.** `QUANT_ENGINE_URL`은 실제로 연결돼
  있는지 이 ADR에서 확인하지 않았다 — 같은 종류의 의심을 받을 만하다([engineering-backlog §9](../engineering-backlog.md)).
- **호가창 단일 라이터 문제의 주소가 정확해진다**: api. HPA가 켜진 채 LIMIT 주문 체결을 실제로 구현하는 순간
  §6.8이 선행돼야 한다. 그 전까지는 호가창이 inert라 안전하지만, 그 사실은 이 ADR과 ADR-043 노트에만 있다 —
  `OrderSagaOrchestrator`에 주석으로 남긴다.
- **`trading.order-filled` 토픽·`monticker-quant-engine` 그룹은 유지된다.** ADR-040의 토픽 선언이 이미 api에 있다.
- Stage 5를 되돌리는 것이므로 architecture.md의 Scaling Roadmap 표를 고친다. ADR-009는 quant-engine 위임 사례로 유효하다.

## Revisit When

- **scale-out-plan §6.8 샤딩을 실행할 때** — 그때 매칭은 api 밖의 "샤드당 단일 라이터" 프로세스가 된다. 그 프로세스는
  이 복사본이 아니라 api의 matching 모듈을 뽑아낸 것이어야 한다.
- **LIMIT 주문의 호가창 체결을 실제로 구현할 때** — HPA replicas > 1인 api에서는 안 된다. 샤딩이 선행 조건이다.
