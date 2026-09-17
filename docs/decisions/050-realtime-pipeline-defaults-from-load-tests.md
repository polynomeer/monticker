# ADR-050: 실시간 시세 파이프라인의 기본값을 부하·순서 실험 결과로 바꾼다 (acks, 리밸런스, WS 한도)

## Status
Accepted

## Context

블로그 Track M 실험 2종을 실제 코드에 대해 돌렸다 — [reports/M-001](../../reports/M-001.md)(WS push vs REST polling·Kafka
정지·느린 소비자), [reports/M-002](../../reports/M-002.md)(파티션 P × 컨슈머 C 순서 보장). 각 조건 3회 이상, p50/p95/p99.
로컬 한 대 측정(페이퍼 트레이딩 규모)이라 절대값이 아니라 **조건 간 비교와 재현된 결함**을 근거로 쓴다.

실험이 재현한 결함 다섯 가지가 전부 **비기능 요건(순서 보장·가용성·확장 한계)이 걸린 기본값**이었고, 코드 로직이 아니라
설정·상수의 선택 문제였다. 그래서 ADR 기준("비기능 요건이 설계에 영향")에 해당해 여기에 결정을 모은다. 배경 수치는
보고서에, 여기서는 **무엇을 왜 그 값으로 정했는가**만 남긴다.

관련 기존 결정: [ADR-005](005-go-market-gateway-kafka.md)(Go 게이트웨이·키=stockId), [ADR-038](038-per-stock-topic-broadcast.md)
(브로드캐스트 컨슈머·conflation), [ADR-040](040-kafka-topics-as-code.md)(토픽·파티션). 이들을 번복하지 않고 그 위의 기본값을 채운다.

## Decision

### 1. 게이트웨이 프로듀서를 acks=all 로 (D-M2-01, D-M2-02)

- `kafka-go` `Writer.RequiredAcks` 기본값은 `RequireNone`(**acks=0**)이다. 브로커 응답을 기다리지 않아 같은 파티션에서도
  배치가 뒤바뀌어 적재됐고(키=stockId인데 종목별 seq 35가 34보다 먼저 — Kafka 로그로 확인, P>1 27회 중 9회), 브로커 장애 시
  유실을 프로듀서가 인지조차 못 한다. → `RequiredAcks: RequireAll`. 복제 계수 1인 로컬에선 acks=1과 같고 운영(RF≥2)에서 맞는 값.
  실험 재현용으로만 `KAFKA_REQUIRED_ACKS=none`.
- 게이트웨이가 발행 중 SIGTERM을 받으면 `Writer.Close()`가 `WaitGroup.Wait`에서 무한 정지했다(종목 고루틴이 Close 뒤에
  `WriteMessages`를 불러 아무도 닫지 않는 파티션 라이터를 만든다). → `generator.RunWith`가 종목 고루틴 종료를 기다린 뒤 Close.

### 2. worker 컨슈머를 짧은 세션 타임아웃 + cooperative 리밸런싱으로 (D-M2-03)

- Kafka 3.x 기본값(`session.timeout.ms` 45s, eager 어사이너)에서 worker 하나가 SIGKILL되면 그 파티션(틱의 절반)이 **45.0초**
  멈추고, 대체 프로세스가 합류하면 **생존자까지 전 파티션을 내려놓고 17–18.5초** 멈췄다 — 크래시 하나가 시세 전체를 세운다.
  → `session.timeout.ms=10000`, `heartbeat.interval.ms=3000`(Kafka 3.0 이전 기본값), `CooperativeStickyAssignor`.
  수정 후 인계 10.6–12.5초, 생존자 정지 ≤287ms, 유실·중복·순서 위반은 수정 전후 모두 0.
- 운영 롤링 배포 시 eager→cooperative 전환은 두 단계(양쪽 어사이너 나열 → cooperative만)로 해야 한다(Kafka 문서).

### 3. WS 연결 상한과 느린 소비자 격리 (D-M1-02, D-M1-04)

- 한 api pod의 동시 WS 연결이 Tomcat `max-connections` 기본 **8,192**에서 막혔다(넘친 연결은 조용히 실패). WS 세션은
  연결당 힙 ~140KB를 쓴다. → `server.tomcat.max-connections` 설정화(기본 20000), **`-Xmx`와 같이** 정한다. 진짜 확장은
  세로가 아니라 pod 증설이며 그건 ADR-038(브로드캐스트 컨슈머가 그룹 없이 전 파티션 수신)이 이미 풀어 뒀다.
- 읽지 않는 WS 연결이 `clientOutboundChannel` 스레드(기본=코어 수)보다 많으면 **모든** 클라이언트가 p99 ~19초 멈췄다.
  send 시간/버퍼 한도만으로는 부족했다(느린 세션은 3초에 끊겨도 발행 스레드가 붙잡힘). → 세션 한도(`sendTimeLimit` 2s,
  버퍼 256KB)로 폭주 세션을 끊고, `app.ws.outbound-core-pool-size`로 발행 풀을 넓힌다(풀 50에서 정상 p99 196ms).

### 파이프라인이 잘한 것 (바꾸지 않는다)

- 키=stockId(ADR-005)는 acks를 고친 뒤 45회 순서 위반 0 — 라운드로빈은 빠른 종목에서 곧 깨지므로 유지가 맞다.
- STOMP push + conflation(ADR-038)은 폴링 대비 서버 CPU 1/20 — WS push 유지가 답이다(D-M1-01).
- Kafka 정지 후 worker·api 컨슈머는 재기동 없이 자동 재접속했다(M-001 §4.3).

## Reasons

- 다섯 결함 모두 **기본값 하나로 발생하고 기본값 하나로 사라졌다** — 로직 변경이 아니라 상수 선택이라, 코드 여러 곳에
  흩어진 주석보다 한 ADR에 근거와 수치 출처를 모으는 편이 재검토에 낫다.
- 값은 임의로 고르지 않고 **재현된 측정에서 역산**했다(예: max-connections는 8,192 상한 관측 + 연결당 힙, 풀 50은 느린
  소비자 25 + 여유). 실험 재현용 env(`KAFKA_REQUIRED_ACKS`, `KAFKA_SESSION_TIMEOUT_MS`)를 남겨 전/후를 다시 잴 수 있다.

## Consequences

- **acks=all**: 게이트웨이 종목당 발행률이 배치 왕복에 묶여 ~90 tick/s로 상한이 생긴다(M-002 §4.6). 실제 체결가 레이트에는
  여유가 있으나, 폭주 종목을 흉내 내려면 Async 발행이 필요하다.
- **짧은 session.timeout**: heartbeat가 더 촘촘해져 긴 GC 정지에 리밸런스가 더 민감하다. 10초 밑으로 더 줄이려면
  정적 멤버십(`group.instance.id`)이 낫다.
- **max-connections↑·풀↑**: 힙·스레드 예산을 함께 올려야 한다(연결당 ~140KB). 값은 운영 ConfigMap에서 pod 스펙에 맞춰 잡는다.
- 게이트웨이 유실은 여전히 "인지된 유실"일 뿐 복구 후 재발행은 없다(M-001 §4.3) — 무손실이 필요해지면 별도 결정이 필요하다.

## Revisit When

- 실제 KIS/Toss 체결가로 종목당 초당 수십 건이 들어와 게이트웨이 acks 상한(~90/s)이 병목이 될 때.
- api pod당 WS 연결이 ~8,000에 접근하거나 HPA 없이 세로 확장을 고려할 때.
- 틱 레이트가 커져 파티션 수(현재 로컬 12·최적 관측 6)를 다시 정해야 할 때 — ADR-040과 함께 재검토.
- 무손실 시세가 요건이 될 때(게이트웨이 버퍼·재발행).
