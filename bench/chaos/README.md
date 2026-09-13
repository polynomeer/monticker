# 카오스 실험 스크립트

[resilience-plan §6](../../docs/resilience-plan.md)의 실험 카탈로그를 실행 가능한 형태로 옮긴 것.
각 스크립트 상단에 **가설·중단 조건·실행 방법**이 있다. 결과는 `docs/resilience-plan.md` §6.4에 기록한다.

| 스크립트 | 실험 | 최초 실행 | 결과 |
|---------|------|----------|------|
| `ch01-redis-down.sh` | Redis 전면 정지 | 2026-09-11 | **PASS** (수정 2건 후) |
| `ch02-redis-latency.sh` | Redis 2초 지연 (toxiproxy) | 2026-09-11 | **PASS** |
| `ch03-postgres-down.sh` | Postgres 정지 | 2026-09-11 | **PASS** — readiness 503/liveness 200, MTTR 2s |
| `ch04-elasticsearch-down.sh` | ES 정지 | 2026-09-11 | **PASS** (수정 1건 후) — ES가 컨테이너에서 한 번도 연결된 적 없던 것을 발견 |
| `ch06-broker-latency.sh` + `kis-stub.py` | KIS 4초 지연 (slow-call) | 2026-09-11 | **PASS** (수정 2건 후) — 운영 브로커 클라이언트 부팅 불가를 발견 |
| `ch05-kafka-down.sh` | Kafka 브로커 정지 (Outbox 실증) | 2026-09-13 | **PASS** (수정 3건 후) — Outbox가 한 번도 발행한 적 없던 것, 리스너 스레드 누수를 발견 |
| `ch07-sigkill.sh` | API 프로세스 SIGKILL 중 주문 | 2026-09-13 | **PASS** — 미완료 0, 12명 대사 불일치 0, 재기동 15s |
| `ch09-rebalance-storm.sh` | 틱 스톰 중 워커 재시작 10회 | 2026-09-13 | **PASS** — 그룹 LAG 0, DLT 0. 랙은 브로커 쪽으로 볼 것 |

## 원칙

- **원본 환경에서 돌리지 않는다.** 로컬 docker-compose 또는 스테이징.
- 스크립트는 정상 상태 → 주입 → 관측 → 복구 → 복구 후 순서를 지키고, 복구 단계는 실패해도 반드시 실행한다.
- 실험이 실패하는 건 성공이다. 알람이 안 울린 것이 진짜 실패다.

## 로컬 실행 예 (포트 충돌 회피 포함)

```bash
# 인프라 (다른 프로젝트와 포트가 겹치면 오버라이드)
export POSTGRES_PORT=55432 REDIS_PORT=56379 MONGODB_PORT=57017 ELASTICSEARCH_PORT=59200 MAILHOG_SMTP_PORT=51025 MAILHOG_WEB_PORT=58025
docker compose up -d postgres redis mongodb elasticsearch mailhog

# API (backend/api 에서). KAFKA_BROKERS는 없는 주소로 — 9092가 남의 브로커일 수 있다(ADR-029 사고)
DB_URL=jdbc:postgresql://localhost:55432/monticker REDIS_PORT=56379 \
MONGODB_URI='mongodb://monticker:monticker@localhost:57017/monticker?authSource=admin' \
ELASTICSEARCH_URI=http://localhost:59200 MAIL_HOST=localhost MAIL_PORT=51025 MAIL_USERNAME=t MAIL_PASSWORD=t \
KAFKA_BROKERS=localhost:1 SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --args='--server.port=58080'

# 실험
API=http://localhost:58080 COMPOSE_ENV="REDIS_PORT=56379" bench/chaos/ch01-redis-down.sh

# CH-06은 KIS 스텁 + 운영 브로커 모드가 필요하다
DELAY_MS=0 PORT=59443 python3 bench/chaos/kis-stub.py &
# API를 추가로 BROKERAGE_MOCK_ENABLED=false KIS_BASE_URL=http://localhost:59443 로 기동
API=http://localhost:58080 STUB=http://localhost:59443 bench/chaos/ch06-broker-latency.sh
```

## CH-05 / 07 / 09 (Kafka + 워커 필요)

```bash
# 인프라에 kafka 추가. 로컬 Docker VM 메모리가 빠듯하면 elasticsearch는 내려도 된다(api는 DB 폴백으로 동작).
export KAFKA_PORT=59092 KAFKA_EXTERNAL_PORT=29092   # + 위의 포트들
docker compose up -d postgres redis mongodb kafka mailhog

# api·worker는 jar로 띄운다 — CH-07이 PID를 죽이고 같은 jar를 재기동한다
(cd backend/api && ./gradlew bootJar -x test); (cd backend/worker && ./gradlew bootJar -x test)
KAFKA_BROKERS=localhost:29092 SERVER_PORT=58080 … java -jar backend/api/build/libs/api-0.0.1-SNAPSHOT.jar &
KAFKA_BROKERS=localhost:29092 SERVER_PORT=58081 KAFKA_CONSUMER_CONCURRENCY=4 java -jar backend/worker/build/libs/worker-0.0.1-SNAPSHOT.jar &

API=http://localhost:58080 COMPOSE_ENV="KAFKA_PORT=59092 KAFKA_EXTERNAL_PORT=29092 …" bench/chaos/ch05-kafka-down.sh
API=http://localhost:58080 bench/chaos/ch07-sigkill.sh          # 재기동 환경변수는 호출 셸에서 상속
GATEWAY=$PWD/services/market-gateway/market-gateway WORKER_JAR=$PWD/backend/worker/build/libs/worker-0.0.1-SNAPSHOT.jar \
  WORKER=http://localhost:58081 bench/chaos/ch09-rebalance-storm.sh
```

