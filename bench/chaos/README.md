# 카오스 실험 스크립트

[resilience-plan §6](../../docs/resilience-plan.md)의 실험 카탈로그를 실행 가능한 형태로 옮긴 것.
각 스크립트 상단에 **가설·중단 조건·실행 방법**이 있다. 결과는 `docs/resilience-plan.md` §6.4에 기록한다.

| 스크립트 | 실험 | 최초 실행 | 결과 |
|---------|------|----------|------|
| `ch01-redis-down.sh` | Redis 전면 정지 | 2026-09-11 | **PASS** (수정 2건 후) |
| `ch02-redis-latency.sh` | Redis 2초 지연 (toxiproxy) | 2026-09-11 | **PASS** |

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
```
