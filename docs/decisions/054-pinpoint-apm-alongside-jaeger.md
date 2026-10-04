# ADR-054: Pinpoint APM을 Jaeger와 함께 두되, 로컬 전용으로 범위를 제한한다

## Status
Accepted

## Context

관측 스택에 Pinpoint APM이 들어온 시점(`d9d3605`, `infra/pinpoint/` + compose `pinpoint`
프로파일)에 ADR을 쓰지 않았다. 그래서 **"이미 OTel → Jaeger가 있는데 왜 APM을 또 두는가"**에
답하는 문서가 없었고, 둘 중 어느 것을 먼저 열어야 하는지도 적혀 있지 않았다.
이 ADR은 뒤늦게 그 결정을 기록한다 — 설계를 바꾸는 문서가 아니라, 이미 있는 것의 근거와
**범위 한계**를 명시하는 문서다.

### 현재 깔려 있는 세 계층

| 계층 | 구현 | 보는 것 | 범위 |
|---|---|---|---|
| 메트릭 | Micrometer → Prometheus → Grafana(대시보드 5종, 알람 27개) | **집계** — 비율·분위·추세 | 로컬 + K8s |
| 분산 추적 | `micrometer-tracing-bridge-otel` → OTLP → Jaeger | **요청 하나가 서비스들을 지나간 경로** | 로컬 + K8s |
| APM | Pinpoint 3.1.0 에이전트 → collector → HBase → web(`:18080`) | **그 요청 안에서 JVM이 한 일** (메서드 호출 스택, 실행된 SQL, GC/힙/스레드) | **로컬만** |

### 겹치는 부분과 겹치지 않는 부분

Jaeger와 Pinpoint는 "요청 하나를 따라간다"는 점에서 **상당히 겹친다.** 그걸 인정하지 않으면
이 결정은 설명되지 않는다. 겹치지 않는 지점은 하나다:

**OTel 스팬은 우리가 미리 정한 곳에만 생긴다.** 자동 계측(Spring MVC, RestClient, Kafka)이
경계는 잡아주지만, 그 경계 안쪽은 `Tracing.span()`을 직접 부른 곳만 보인다. 즉 **무엇이 느린지
이미 알고 있어야 계측할 수 있다.** p99가 튀었는데 원인을 모르는 상황에서는, 그때 이미 전부
기록하고 있던 도구가 필요하다 — Pinpoint 에이전트는 바이트코드 조작으로 메서드 단위와 SQL을
코드 수정 없이 들고 있다.

### 고려한 대안

| 대안 | 왜 택하지 않았나 |
|---|---|
| **Jaeger만 쓰고 필요할 때 스팬 추가** | 답을 미리 알아야 계측할 수 있다. 재현이 어려운 지연은 그 사이에 지나간다 |
| **상용 APM (Datadog / New Relic)** | 비용이 선형으로 늘고, 트레이스에 사용자 데이터가 섞여 외부로 나간다. 개인 프로젝트 단계에서 정당화되지 않는다 |
| **필요할 때만 async-profiler / JFR 접속** | UI도 이력도 없고 pod에 들어가야 한다. "느렸던 그 순간"은 이미 지났다 |
| **Pinpoint만 쓰고 Jaeger 제거** | Pinpoint는 Java 에이전트다. Go `market-gateway`를 볼 수 없고, OTLP 생태계(향후 수집기 교체)와 분리된다 |

## Decision

**셋을 다 둔다. 단 Pinpoint의 범위를 명시적으로 제한한다.**

1. **열어보는 순서를 정한다.** Grafana(무엇이 언제부터) → Jaeger(어느 서비스/구간) →
   Pinpoint(그 안에서 어느 메서드·어느 SQL). 이 순서를 적어두지 않으면 매번 "어디를 봐야 하지"가
   된다.

2. **에이전트는 기본 꺼짐.** Dockerfile이 에이전트를 **이미지에는 넣되**(별도 스테이지, 레이어
   캐시), `PINPOINT_ENABLE=true`일 때만 `-javaagent`를 주입한다. 로컬·CI 기동 속도를 깎지 않는다.

   ```dockerfile
   ENTRYPOINT ["sh", "-c", "\
     if [ \"${PINPOINT_ENABLE:-false}\" = \"true\" ]; then \
       AGENT_JAR=$([ -f /pinpoint-agent/pinpoint-bootstrap.jar ] \
         && echo /pinpoint-agent/pinpoint-bootstrap.jar \
         || ls /pinpoint-agent/pinpoint-bootstrap-*.jar | head -1); \
       AGENT_OPTS=\"${AGENT_JAR:+-javaagent:$AGENT_JAR} -Dpinpoint.agentId=... \"; \
     fi; \
     exec java ${AGENT_OPTS} ${JAVA_OPTS} -jar app.jar"]
   ```

3. **K8s에는 넣지 않는다.** Pinpoint는 HBase를 요구하고(스키마 초기화 2~3분), collector·web까지
   세 컴포넌트다. 운영에서 그걸 지탱하는 비용이 얻는 것보다 크다 — 운영에서는 Prometheus 집계와
   Jaeger 트레이스로 충분하고, 메서드 단위가 필요한 조사는 로컬 재현으로 옮긴다.
   `configmap.yaml`에 `PINPOINT_ENABLE: "false"`만 남겨 "일부러 끈 것"임을 드러낸다.

## 기록해 둘 것 — 이 ADR을 쓰면서 찾은 결함

`ARG PINPOINT_VERSION=3.1.0`은 다운로드 스테이지에만 쓰이고, 런타임 `ENTRYPOINT`에는
`pinpoint-bootstrap-3.1.0.jar`가 **리터럴로 박혀 있었다.** api·worker 양쪽 모두. 버전을
올리면 ARG는 새 tarball을 받아오는데 ENTRYPOINT는 없는 파일을 가리켜, 에이전트가 붙지 않거나
JVM이 `-javaagent` 오류로 죽는다. 버전 핀이 한 파일 안에서 둘로 갈라져 있었던 셈이다.

tarball을 실제로 받아 내용을 확인해보니 루트에 **버전 없는 `pinpoint-bootstrap.jar`가 함께 들어
있다**(버전 붙은 jar 과 같은 크기·같은 날짜의 동일 파일). 그쪽을 쓰면 버전 핀이 애초에 한 곳으로
모인다. 그 파일을 우선 쓰고, 미래 릴리스가 빼더라도 글롭으로 떨어지게 했으며, 둘 다 없으면 그
사실을 stderr에 찍고 에이전트 없이 기동한다. 네 경우(둘 다 있음 / 버전 jar만 / 없음 / 비활성)를
셸에서 직접 실행해 확인했다.

### `infra/pinpoint/` 의 설정이 **한 번도 적용된 적이 없었다**

에이전트 스테이지를 실제로 빌드해 이미지 안을 들여다보니, 적용 중인 설정은 tarball 기본값
(377줄)이었다. 리포의 `infra/pinpoint/`는 Dockerfile이 `COPY`하지도, compose가 마운트하지도
않아 **아무 곳에서도 읽히지 않는 파일**이었다. 즉 Pinpoint는 켜더라도 다음 상태였다:

| 키 | 리포 의도 | 실제 (tarball 기본값) |
|---|---|---|
| `profiler.transport.grpc.collector.ip` | `pinpoint-collector` | **`127.0.0.1`** |
| `profiler.sampling.counting.sampling-rate` | 10 | 1 |
| `profiler.opentelemetry.sdk.api.trace.enable` | `false` (이중 계측 방지) | (미설정) |
| `profiler.jdbc.postgresql.tracesqlbindvalue` | `false` (민감정보) | **`true`** |
| `profiler.kafka.{producer,consumer}.enable` | `true` | `false` |
| `profiler.resttemplate.enable` | `true` | `false` |
| `profiler.logback.logging.transactioninfo` | `true` | `false` |

첫 줄이 치명적이다. **컨테이너 안의 127.0.0.1에는 collector가 없다** — Pinpoint는 단 한 번도
데이터를 받은 적이 없다. CH-05(아웃박스가 한 번도 발행한 적 없음), 정기결제 갱신 배치와 같은
계열이다. 넷째 줄도 가볍지 않다. 리포 설정이 일부러 끈 SQL 바인딩 값 수집이 기본값에서는 켜져
있어서, 켜는 순간 쿼리 파라미터가 APM으로 흘러간다.

그리고 리포 설정을 그냥 마운트해도 collector 주소는 고쳐지지 않았다. 에이전트를 직접 띄워가며
배너의 `GrpcTransportConfig`로 확인한 메커니즘은 이렇다:

1. **root config가 profile보다 우선한다.** `profiler.transport.grpc.collector.ip`·샘플링 7개 키는
   tarball root가 이미 정의하므로, profile에 써도 무시된다.
2. **`${PINPOINT_COLLECTOR_IP:pinpoint-collector}` 플레이스홀더는 해석되지 않는다.** 배너에
   치환되지 않은 문자열이 그대로 찍힌다. compose가 넘기던 `PINPOINT_COLLECTOR_IP`는 죽은 변수였다.
3. **리포의 `pinpoint-root.config`(11줄)로 root를 덮으면 더 나빠진다.** tarball root가 들고 있던
   `profiler.transport.grpc.{agent,stat,span}.collector.ip=${profiler.transport.grpc.collector.ip}`
   간접 참조가 사라져, 어떤 값을 줘도 채널별 코드 기본값(127.0.0.1)으로 떨어진다.
4. **시스템 프로퍼티(`-D`)만 root를 이긴다.**

그래서 고친 방식:

- 리포의 `pinpoint-root.config`를 **삭제**했다 — 그 파일이 문제의 원인이었다. tarball의 root를
  그대로 쓴다.
- collector 주소와 샘플링은 ENTRYPOINT가 `-D`로 넘긴다(`PINPOINT_COLLECTOR_IP`,
  `PINPOINT_SAMPLING_RATE` 환경변수 → 시스템 프로퍼티). 이제 compose의 그 변수가 실제로 작동한다.
- 나머지 26개 키(플러그인·OTel·SQL 바인딩)는 profile에서 정상 적용되므로, compose가
  `profiles/release/pinpoint.config`를 bind mount로 덮는다. Pinpoint는 로컬 전용이라 compose가
  유일한 적용 지점이고, 빌드 컨텍스트가 `backend/{api,worker}`여서 Dockerfile에서는 닿지 못한다.
- profile 파일 상단에 "여기에 써도 무시되는 키" 목록을 적어, 다음 사람이 같은 함정에 빠지지
  않게 했다.

마지막으로 에이전트를 띄워 전부 확인했다: collector `pinpoint-collector:9991~9993`,
샘플링 10, OTel 이중 계측 off, SQL 바인딩 off, Kafka·RestTemplate·logback txId on,
`pinpoint agent started normally`.

### 그리고 스택 자체가 기동할 수 없었다

위 설정 결함을 고친 뒤 `--profile pinpoint`로 실제로 올려보려 했다. **한 단계도 넘어가지 못했다.**
막힌 지점을 순서대로 적는다 — 전부 compose 파일의 문제였고, 하나하나가 독립적으로 치명적이었다.

| # | 증상 | 원인 |
|---|---|---|
| 1 | HBase 가 `KeeperErrorCode = OperationTimeout` 으로 영원히 맴돈다 | 이미지의 `hbase-site.xml` 에 `hbase.cluster.distributed=true` 와 `hbase.zookeeper.quorum=zoo1,zoo2,zoo3` 가 **구워져** 있다. compose 에 zoo1~3 이 없었다 |
| 2 | HBase 가 영원히 `unhealthy` | healthcheck 가 `hbase zkcli -server localhost:2181` — ZK 가 이 컨테이너 안에 있다는 전제(틀렸다)에, `hbase` 가 비로그인 `sh` 의 PATH 에도 없다 |
| 3 | 2번을 고쳤는데도 `unhealthy` | compose 가 healthcheck 문자열의 `$HBASE_HOME` 을 **호스트** 환경변수로 치환해 빈 문자열로 만든다 → `/bin/hbase`. `$$` 로 이스케이프해야 한다 |
| 4 | collector/web 이 2181 에 `Connection refused` | `PINPOINT_ZOOKEEPER_ADDRESS: pinpoint-hbase` — 1번과 같은 오해 |
| 5 | collector/web 이 부팅을 거부 | **`pinpoint-hbase:2.5.4` 는 HBase 1.2.6 을 담고 collector/web `3.1.0` 은 HBase 2.x 를 요구한다.** `HBase version compatibility violation … supportedVersion=[2.] V2, HBaseServer:1.2.6`. 태그 라인이 어긋나 있었다 |
| 6 | HBase 마스터가 `hbase:meta is NOT online` 으로 멈춘다 | hbase 볼륨만 지우고 ZooKeeper 를 그대로 뒀다. HBase 상태는 ZK 에도 있다 — 죽은 이전 컨테이너 호스트명이 `/hbase` znode 에 남아 있었다 |
| 7 | 마스터가 `session … has expired` 뒤 `aborting server` | ZooKeeper 는 세션 타임아웃을 `20 × tickTime`(기본 **40s**)으로 상한을 건다. HBase 는 90s 를 요청하지만 깎인다. 에뮬레이션 환경의 GC·스톨이 40s 를 넘긴다 |
| 8 | 마스터가 `TraceV2` 리전 생성 중 로그도 없이 사라진다 | 힙 부족. `-XX:OnOutOfMemoryError=kill -9 %p` 라 **아무 기록도 남지 않는다**. 반대로 크게 주면 master+regionserver 두 JVM 합이 Docker VM 을 넘겨 같은 결과가 된다 |

5번이 특히 분명한 증거다. **collector 와 web 은 이 compose 파일로 단 한 번도 부팅한 적이 없다** —
HBase 버전이 맞지 않아 컨텍스트 생성 단계에서 거부하므로, 누군가 한 번이라도 올려봤다면 즉시 터졌을
것이다. 이미지 태그 세 개가 서로 맞는지 아무도 확인하지 않았다는 뜻이다.

1~7 은 compose 에서 고쳤다(ZooKeeper 서비스 1대에 `zoo1/zoo2/zoo3` 을 alias 로 붙여 업스트림의
ZK 3대 대신 쓴다 — 로컬 프로파일링에 ZK 고가용성은 필요 없다). 8 은 환경 문제라 힙 기본값과
전제조건을 주석으로 남겼다.

**그래도 이 머신에서는 끝까지 못 갔다.** 1~7 을 고친 뒤 HBase 는 ZooKeeper 에 붙고 마스터가
뜨고 healthcheck 도 통과하지만, 스키마 15개 중 마지막 `TraceV2`(리전 수백 개로 사전 분할된다)를
만드는 도중 마스터가 **로그 한 줄 없이 사라진다**. 힙을 1.5GB·2.5GB 로 바꿔가며 여러 번 재시도했고,
한 번은 40초 만에 15개가 다 생겼다가 다시 같은 지점에서 죽었다 — 재현이 불안정하다.

조건: Apple Silicon에서 `linux/amd64` 전용 이미지를 QEMU 에뮬레이션으로 돌리고, Docker VM 7.65GB
중 약 2.5GB를 다른 프로젝트 컨테이너가 쓰고 있었다. master·regionserver 두 JVM 이 각각 `-Xmx` 를
받으므로 남은 메모리로는 빠듯하다. **이건 리포의 결함이 아니라 환경 한계로 본다** — 다만 "본
것"과 "못 본 것"을 섞지 않기 위해 여기 적는다.

따라서 **end-to-end 검증은 여전히 미완이다.** 지금까지 확인된 것과 아직 아닌 것:

| | 상태 |
|---|---|
| 에이전트가 설정을 읽고 collector 주소·샘플링·플러그인을 의도대로 해석한다 | ✅ 실측 (배너 `GrpcTransportConfig`) |
| ZooKeeper·HBase 가 기동하고 Pinpoint 스키마가 만들어진다 | 🟡 한 번 성공(15/15), 재현 불안정 |
| collector·web 이 부팅한다 | ❌ HBase 스키마가 안정되기 전까지 확인 불가 |
| 에이전트 → collector → HBase → UI 에 트랜잭션이 찍힌다 | ❌ **미확인** |

다음 시도 전 전제조건: Docker VM 메모리를 8GB 이상으로 올리고, 다른 프로젝트 컨테이너를 내린
상태에서 `--profile pinpoint` 만 띄운다. 그리고 `TraceV2` 가 생성될 때까지(수 분) 기다린 뒤
collector/web 을 올린다.

같은 맥락에서 하나 더 나왔다. 결제 PG 브레이커(`tossPg`,
[ADR-053](053-payment-idempotency-and-failure-classification.md))가 알람 분류에서
`NonBrokerCircuitOpen`(warning, 5분)에 섞여 있었다. 그 알람의 전제는 **"폴백이 있어 사용자가
즉시 아프지는 않다"**인데, 결제에는 폴백이 없다 — 열리면 신규 구독·카드 등록·정기결제 갱신이
전부 멈춘다. `PaymentCircuitOpen`(critical, 30s)을 분리하고 Trading 대시보드의 브레이커 패널
필터도 `kis|toss` → `kis|toss|tossPg`로 넓혔다. **돈이 오가는 브레이커 셋을 한 화면에서 본다**는
기준으로 통일했다.

## Reasons

**왜 "APM 하나로 통일"하지 않는가.** 통일의 대가가 각각 다르다. Pinpoint로 통일하면 Go 게이트웨이와
OTLP 생태계를 잃는다. Jaeger로 통일하면 "원인을 모를 때 들여다볼 것"을 잃는다. 둘 다 끄고 메트릭만
남기면 집계 뒤에 숨은 개별 요청을 영원히 못 본다.

**왜 그래도 운영에서는 Pinpoint를 포기하는가.** 운영에서 필요한 건 *감지*이고, 감지는 메트릭과
알람이 한다. 메서드 단위 분석은 감지 다음 단계이고 그건 재현 환경에서 해도 된다 — HBase 클러스터를
운영하는 비용보다 그 전환 비용이 싸다. 이 판단은 트래픽이 커지면 바뀔 수 있다(§Revisit).

**왜 범위를 ADR로 못 박는가.** 끄여 있는 인프라는 "왜 꺼져 있지?"를 반복해서 유발하고, 결국
누군가 아무 근거 없이 켠다. `PINPOINT_ENABLE: "false"`가 실수가 아니라 결정이라는 걸 적어둔다.

## Consequences

**볼 곳이 셋이다.** 온콜이 어디를 먼저 열지 헷갈린다. 위의 순서 규칙이 그 완화책이지만, 규칙은
문서에만 있고 도구가 강제하지 않는다. **런북에 이 순서가 반영돼 있지 않다** —
[runbooks/](../runbooks/README.md)는 Grafana·Jaeger만 언급한다.

**에이전트 오버헤드를 측정하지 않았다.** 기본값을 꺼둔 이유가 "기동 속도"인데, 정작 켰을 때의
지연·처리량 영향은 재본 적이 없다. Pinpoint 문서가 말하는 수 % 수준을 그대로 믿고 있는 상태다.
운영 도입을 검토할 때는 L-01/L-05를 에이전트 on/off로 돌려 실측해야 한다.

**로컬에서만 쓰는 도구는 녹슨다.** 위의 설정 미적용이 바로 그 증거다 — 아무도 켜보지 않았으니
아무도 몰랐다. `--profile pinpoint`를 몇 달 안 돌리면 이미지 태그·HBase 초기화·스키마도 같은
식으로 조용히 깨진다. 지금은 그걸 잡는 CI가 없고, **설정이 실제로 적용되는지 확인하는 테스트도
없다**(에이전트를 띄워 배너를 대조하는 것 말고는 방법이 마땅치 않다).

**end-to-end로는 아직 확인하지 않았다.** 위 검증은 에이전트가 **어디로 보내려 하는지**까지다.
`--profile pinpoint`로 HBase·collector·web을 전부 올려 UI에 트랜잭션이 찍히는 것을 본 적은
없다(HBase 초기화 2~3분 + api 이미지 전체 빌드). 그걸 보기 전까지 "Pinpoint가 동작한다"고
말하지 않는다.

**Go 게이트웨이는 세 계층 어디에도 없다.** Pinpoint는 Java 전용이라 당연하지만, **OTel 계측도
들어가 있지 않다** — `services/market-gateway`에 추적 코드가 0줄이다. 즉 틱 파이프라인의
수집 구간은 트레이스가 끊긴다. 이건 Pinpoint의 한계가 아니라 그냥 갭이다.

**Jaeger가 인메모리다.** pod가 재시작되면 트레이스가 사라진다(resilience-plan §4.1). 사후
분석에는 못 쓰므로, "원인을 모를 때 들여다볼 것"이 운영에서는 사실상 없다 — 이 ADR이 Pinpoint를
로컬로 제한한 판단은 그 갭을 **메우지 않는다**.

## Revisit When

- 운영에서 "메트릭·트레이스로는 원인을 못 찾은" 장애가 두 번 이상 반복될 때 — 그때가 운영 APM의
  비용이 정당화되는 시점이다
- Jaeger에 영속 저장소(Elasticsearch/Cassandra)를 붙일 때 — 사후 분석이 가능해지면 Pinpoint의
  역할이 줄어든다. 역할이 겹치면 하나를 접는다
- OTel 자동 계측이 메서드·SQL 수준까지 실용적으로 커버하게 될 때 — 겹침이 전부가 되면 Pinpoint를 접는다
- Go 게이트웨이에 OTel을 넣을 때 — 추적 범위가 달라지므로 위 표를 다시 쓴다
- 에이전트 오버헤드를 실측했을 때 — 기본값(off)의 근거를 측정으로 교체한다
