# reports — 실험 보고서

> **페이퍼 트레이딩 규모의 개인 프로젝트다. 여기 숫자는 로컬 한 대에서 잰 것이고 실제 운영 트래픽 검증이 아니다.**

블로그 Track M(실시간 시세 실험)의 monticker 쪽 결과. 형식과 규칙은 parity-pay 관행을 따른다 — 목적·방법·표·분석·증거·환경·
재실행 명령, 조건당 3회 이상, p50/p95/p99, 실험 단위 커밋. 스크립트는 [bench/experiments/](../bench/experiments/README.md).

| 보고서 | 질문 | 상태 |
|---|---|---|
| [M-001](M-001.md) | WS push vs REST polling — N 클라이언트에서 지연·서버 비용, Kafka 정지 시 침묵·유실, 느린 소비자 | 완료 (2026-09-17) — 결함 3건 |
| [M-002](M-002.md) | market.ticks 파티션 P × 컨슈머 C — 처리량·e2e·종목별 순서 위반, 리밸런스 중 중복·유실, 핫 종목 HOL | 완료 (2026-09-17) — 결함 3건 |
| [M-002-tail](M-002-tail.md) | 실시간 p95 꼬리 스파이크 규명 (M-002 §4.4 후속) | 완료 (2026-09-17) — 클래스 A 수정, 클래스 B 는 브로커/호스트로 좁힘 |

이번 실험의 파이프라인 기본값 변경은 [ADR-050](../docs/decisions/050-realtime-pipeline-defaults-from-load-tests.md)으로 승격했다.

## 실험이 찾은 결함

번호는 `D-M<보고서>-<순번>`. 수정 커밋이 있으면 적는다. resilience-plan/backlog 의 P0~P2 번호 체계와는 별개다.

| 번호 | 무엇 | 어디서 | 상태 |
|---|---|---|---|
| D-M2-01 | Go market-gateway 의 kafka-go Writer 가 `RequiredAcks` 미지정 → **acks=0**. 브로커 응답을 기다리지 않아 같은 파티션 안에서 배치 순서가 뒤집히고(종목별 seq 35 가 34 보다 먼저 적재됨, 로그에서 확인), 브로커 장애 시 유실이 조용히 일어난다 | `services/market-gateway/internal/kafkaproducer/producer.go` | M-002 §4.1 — **수정**: `RequiredAcks: RequireAll` (+ `KAFKA_REQUIRED_ACKS` 재현용). 수정 후 키=stockId 45회 위반 0 |
| D-M2-02 | Go market-gateway 가 발행 중 SIGTERM 을 받으면 `kafka-go Writer.Close()` 의 `sync.WaitGroup.Wait` 에서 영원히 멈춘다(28분 후 goroutine 덤프로 확인). K8s 에서는 매 배포마다 terminationGracePeriod 만큼 기다린 뒤 SIGKILL 된다 | `services/market-gateway/main.go` (`defer producer.Close()`) | M-002 §4.5 — **수정**: `generator.RunWith` 가 종목 고루틴 종료를 기다린 뒤 Close. 이후 75회 무재발 |
| D-M2-03 | worker 컨슈머가 `session.timeout.ms` 기본 45s + eager 어사이너. 프로세스 하나가 SIGKILL 되면 그 파티션(틱의 절반)이 **45.0s** 멈추고, 대체 프로세스가 합류하면 **생존자까지 17–18.5s 전 파티션을 내려놓는다** — 크래시 하나가 시세 전체를 멈춘다(3회 재현) | `backend/worker/.../kafka/KafkaConfig.kt` | M-002 §4.3 — **수정**: 10s/3s + `CooperativeStickyAssignor`. 인계 10.6–12.5s, 생존자 정지 ≤287ms |
| D-M1-01 | REST 폴링은 이 서버에서 ~5,300–5,500 req/s 에서 천장을 친다(요청당 캐시 없는 `findById`+Redis 조회, Hikari 10·Tomcat 200 에서 직렬화). 1만 명에게 0.5초 신선도(10만 req/s 필요) 불가 — 95% drop. WS 는 같은 부하를 CPU 1/20 로 처리 | 설계(폴링 대안) | M-001 §4.1 — WS push 유지가 답. 폴링 쓰려면 종목 캐시+배치 엔드포인트 필요 |
| D-M1-02 | 한 api pod 의 동시 WS 연결이 Tomcat `max-connections` 기본 8,192 에서 막힌다(N=10,000 실험, 넘친 연결 조용히 실패). 연결당 힙 ~140KB | `backend/api/.../application.yml` (server.tomcat) | M-001 §4.2 — **수정**: `max-connections` 설정화(기본 20000), `-Xmx` 와 같이 조정. 진짜 확장은 pod 증설(ADR-038) |
| D-M1-04 | 읽지 않는 WS 연결이 `clientOutboundChannel` 스레드(기본=코어 수)보다 많으면 **모든** 클라이언트가 p99 ~19s 멈춘다(느린 세션은 3초에 끊겨도). sendTimeLimit/버퍼 한도만으로는 안 됨 | `backend/api/.../WebSocketConfig.kt` | M-001 §4.4 — **수정**: send 한도(2s/256KB) + `app.ws.outbound-core-pool-size`. 풀 50 에서 정상 p99 196ms |
| D-L05-01 | paper 체결 원장(`LedgerService.recordBuy/recordSell`)이 Modulith 아웃박스 at-least-once 재전달에 멱등하지 않아, 커넥션 풀 고갈로 리스너 완료 표시가 실패하면 5분 뒤 재전달에서 FILL/SETTLEMENT 원장을 **이중 기록** → 대사(ADR-043) 드리프트(user 642: 같은 trade FILL 2회, 5분 간격) | `backend/api/.../wallet/application/LedgerService.kt` | L-05 §4.4 — **수정**: `(paper_trade_id, event_type)` 멱등 체크 + 부분 유니크 인덱스(V46) + 기존 중복 제거. 재검증 드리프트 0 |
