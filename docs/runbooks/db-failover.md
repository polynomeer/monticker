# Postgres 장애·페일오버 — `ApiErrorBudgetBurn` + `HikariPoolNearExhaustion` / `AllReplicasDown`

## 증상
- readiness `/actuator/health/readiness`가 `db: DOWN`으로 503 → K8s가 api·worker pod를 엔드포인트에서 뺀다(P0-6). **liveness는 UP** — 재시작 루프가 나면 안 된다(CH-03 PASS).
- 남은 요청은 Hikari `connection-timeout` 3초 뒤 500(CH-03: DB 없는 동안 500 — 503이 더 정직하다는 관찰은 backlog).
- `hikaricp_connections_pending` 상승, `HikariPendingThreads`.

## 영향 범위
- 전면. 주문·조회·원장·알림 전부. 시세 fan-out만 Kafka→api 메모리 경로라 잠시 산다.
- Outbox·Saga·정산 배치는 DB가 돌아오면 자동 재개(5분 주기).

## 1차 확인 (3단계)
1. **프로세스인가 연결인가**: `pg_isready -h <host>`. 응답 없음 → 프로세스/노드. 응답은 하는데 pending이 높음 → **풀 고갈**(느린 쿼리·락). `SELECT pid, now()-query_start, state, wait_event_type, left(query,80) FROM pg_stat_activity WHERE state <> 'idle' ORDER BY 2 DESC LIMIT 20;`
2. **statement_timeout이 일하고 있는가**: api 30s / worker 60s(P1-3). 30초 넘는 쿼리가 취소되지 않고 남아 있으면 세션 설정이 안 먹은 것.
3. **디스크·WAL**: `df -h` 데이터 볼륨, `pg_wal` 크기. 디스크 풀이면 DB는 살아 있어도 쓰기가 전부 실패한다.

## 완화
- **풀 고갈**: 범인 쿼리 `SELECT pg_cancel_backend(pid)` → 안 되면 `pg_terminate_backend`. 반복이면 인덱스/쿼리 수정.
- **프로세스 다운(단일 인스턴스, 현재 구조)**: 재시작. 재기동 후 **아무것도 안 해도** readiness가 돌아온다(CH-03: MTTR 2초, Hikari 재연결).
- **복제본이 있는 구조(scale-out-plan §6.3.1 이후)**: 승격 절차는 그때 이 절에 쓴다. 지금은 없다 — 복구 수단은 **백업 복원**뿐.
- **데이터 손상/유실**: [infra/db/README.md](../../infra/db/README.md) — `restore.sh`(논리 백업, 매일 03:15 KST) 또는 `pitr-restore.sh`(WAL). 복원 전 **현재 상태 덤프부터**(덮어쓰기 전에).

## 복구 후 검증 (순서대로)
1. readiness UP, `hikaricp_connections_pending` 0.
2. **정합성**: `POST /api/admin/batch/ledger-reconciliation` → mismatch 0 (백업 복원이었다면 복원 시점 이후 거래가 사라졌을 수 있다 — [ledger-mismatch.md](ledger-mismatch.md)).
3. `event_publication` 미완료가 5분 뒤 0으로, `order_sagas` STARTED/COMPENSATING 0.
4. Flyway: `flyway_schema_history` 마지막 버전이 배포된 코드와 같은지(복원본이 오래됐으면 기동 시 마이그레이션이 다시 돈다).
5. ES: DB를 과거로 되돌렸다면 검색 인덱스가 미래를 가리킨다 — `POST /api/admin/search/reindex/{index}`.

## 에스컬레이션
- 5분 내 pg_isready 실패 지속 → 인시던트, 인프라 담당.
- 백업 복원이 필요하면 **복원 범위(시각)를 결정하는 사람**이 필요하다 — 거래 담당과 함께. 복원은 되돌릴 수 없다.
