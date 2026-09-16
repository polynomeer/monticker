# reports — 실험 보고서

> **페이퍼 트레이딩 규모의 개인 프로젝트다. 여기 숫자는 로컬 한 대에서 잰 것이고 실제 운영 트래픽 검증이 아니다.**

블로그 Track M(실시간 시세 실험)의 monticker 쪽 결과. 형식과 규칙은 parity-pay 관행을 따른다 — 목적·방법·표·분석·증거·환경·
재실행 명령, 조건당 3회 이상, p50/p95/p99, 실험 단위 커밋. 스크립트는 [bench/experiments/](../bench/experiments/README.md).

| 보고서 | 질문 | 상태 |
|---|---|---|
| [M-001](M-001.md) | WS push vs REST polling — N 클라이언트에서 지연·서버 비용, Kafka 정지 시 침묵·유실, 느린 소비자 | 진행 중 |
| [M-002](M-002.md) | market.ticks 파티션 P × 컨슈머 C — 처리량·e2e·종목별 순서 위반, 리밸런스 중 중복·유실, 핫 종목 HOL | 진행 중 |

## 실험이 찾은 결함

번호는 `D-M<보고서>-<순번>`. 수정 커밋이 있으면 적는다. resilience-plan/backlog 의 P0~P2 번호 체계와는 별개다.

| 번호 | 무엇 | 어디서 | 상태 |
|---|---|---|---|
| D-M2-01 | Go market-gateway 의 kafka-go Writer 가 `RequiredAcks` 미지정 → **acks=0**. 브로커 응답을 기다리지 않아 같은 파티션 안에서 배치 순서가 뒤집히고(종목별 seq 35 가 34 보다 먼저 적재됨, 로그에서 확인), 브로커 장애 시 유실이 조용히 일어난다 | `services/market-gateway/internal/kafkaproducer/producer.go` | M-002 §4.1 — **수정**: `RequiredAcks: RequireAll` (+ `KAFKA_REQUIRED_ACKS` 재현용). 수정 후 키=stockId 45회 위반 0 |
| D-M2-02 | Go market-gateway 가 발행 중 SIGTERM 을 받으면 `kafka-go Writer.Close()` 의 `sync.WaitGroup.Wait` 에서 영원히 멈춘다(28분 후 goroutine 덤프로 확인). K8s 에서는 매 배포마다 terminationGracePeriod 만큼 기다린 뒤 SIGKILL 된다 | `services/market-gateway/main.go` (`defer producer.Close()`) | M-002 §4.5 — **수정**: `generator.RunWith` 가 종목 고루틴 종료를 기다린 뒤 Close. 이후 75회 무재발 |
| D-M2-03 | worker 컨슈머가 `session.timeout.ms` 기본 45s + eager 어사이너. 프로세스 하나가 SIGKILL 되면 그 파티션(틱의 절반)이 **45.0s** 멈추고, 대체 프로세스가 합류하면 **생존자까지 17–18.5s 전 파티션을 내려놓는다** — 크래시 하나가 시세 전체를 멈춘다(3회 재현) | `backend/worker/.../kafka/KafkaConfig.kt` | M-002 §4.3 — **수정**: 10s/3s + `CooperativeStickyAssignor`. 인계 10.6–12.5s, 생존자 정지 ≤287ms |
