# bench/experiments — 실험(reports/M-0xx) 스크립트

> 페이퍼 트레이딩 규모의 개인 프로젝트다. 여기 숫자는 로컬 한 대(호스트 JVM/Go + Docker VM)에서 잰 것이고
> 실제 운영 트래픽 검증이 아니다. 결과와 해석은 [reports/](../../reports/README.md)에 있다.

규칙(블로그 Track M · parity-pay 관행을 그대로 따른다):

- **설정은 환경변수·`experiment` 프로파일로만 바꾼다.** 코드 기본값은 건드리지 않는다. 게이트웨이 `TICK_SEQ`/`TICK_KEY_MODE`/
  `TICK_HOT_*`, worker `SPRING_PROFILES_ACTIVE=experiment` + `EXP_*` — 기본 프로파일에서는 전부 꺼져 있다.
- 조건마다 **3회 이상** 반복. 재현되지 않는 것은 "증명하지 못함"으로 적는다.
- 분위수는 p50/p95/p99. 평균은 쓰지 않는다.
- 인프라 컨테이너 리소스 상한은 [`compose.limits.yml`](compose.limits.yml)로 건다. api·worker·gateway 는 호스트 프로세스라 상한이 없다 — 보고서 환경 절에 그대로 쓴다.
- 결과 원본(JSON·TSV·로그)은 `reports/M-0xx/raw/` 에 남기고 보고서가 그걸 인용한다.

## 준비

```bash
# 인프라 (대체 포트 스택 + 리소스 상한)
POSTGRES_PORT=55432 REDIS_PORT=56379 KAFKA_PORT=59092 KAFKA_EXTERNAL_PORT=29092 \
  docker compose -f docker-compose.yml -f bench/experiments/compose.limits.yml --profile kafka up -d postgres redis kafka
# 빌드
(cd backend/api && ./gradlew bootJar -x test) && (cd backend/worker && ./gradlew bootJar -x test)
(cd services/market-gateway && go build -o market-gateway .)
```

`lib.sh` 가 기본값으로 위 포트를 쓴다(`PG_PORT REDIS_PORT KAFKA_BROKERS API_PORT WORKER_PORT OUT` 로 바꾼다).

## M-002 — 파티션 × 컨슈머 순서 보장 ([보고서](../../reports/M-002.md))

| 스크립트 | 무엇 | 주요 변수 |
|---|---|---|
| `m2-order-grid.sh` | (a) P×C×키방식 격자: 처리량·e2e·순서 위반·중복·유실 | `P_LIST C_LIST KEYS RUNS HOLD OUT` |
| `m2-rebalance.sh` + `m2-analyze-stream.py` | (b) worker 2프로세스 중 하나 SIGKILL → 재기동. Redis 스트림으로 전 구간 대조 | `P C RUNS KILL_AT RESTART_AT` |
| `m2-hot-stock.sh` | (c) 핫 종목 head-of-line: hot / 같은 파티션 / 나머지 의 e2e | `P C HOT_STOCK HOT_INTERVAL SLOW_MS CASES` |

worker 관측값: `GET http://localhost:58081/experiment/tick-order` (experiment 프로파일에서만).

## M-001 — WS push vs REST polling ([보고서](../../reports/M-001.md))

| 스크립트 | 무엇 | 주요 변수 |
|---|---|---|
| `m1-ws-vs-poll.sh` (+ `m1-ws.js`, `m1-poll.js`) | (a) N 클라이언트: WS / 폴링 1s / 폴링 500ms | `N_LIST MODES RUNS HOLD SUBS` |
| `m1-fallback.sh` (+ `m1-observer.js`) | (b) Kafka `docker kill` → `docker start`: 침묵 시간·유실·자동 재접속 | `N OUTAGE KILL_AT RUNS` |
| `m1-slow-consumer.sh` (+ `m1-slow-consumer.js`) | (c) 읽지 않는 WS 연결 K개: 서버가 끊기까지·다른 클라이언트 영향 | `SLOW_LIST REPEAT N RUNS` |

k6 폴링은 클라이언트 N개를 VU N개로 흉내 내지 않고 **도착률**(N×SUBS/간격 req/s)로 모델링한다 — `m1-poll.js` 주석 참고.
