# 런북

[resilience-plan §8](../resilience-plan.md) — 알람마다 런북이 있어야 한다. 없는 알람은 "누군가 언젠가 보겠지"가 된다.
알람 annotation의 `runbook: <name>`이 이 디렉터리의 파일명이다. 대시보드는 [Grafana](../deployment.md) `/grafana`.

| 런북 | 알람 (alert-rules.yml) | 한 줄 |
|------|------------------------|------|
| [redis-down.md](redis-down.md) | `RedisFailOpenSustained` `IdempotencyStoreDown` | Redis는 fail-open(레이트리밋·캐시)/fail-closed(멱등성 503). 죽어도 API는 산다 — 무엇이 꺼졌는지 알라 |
| [ledger-mismatch.md](ledger-mismatch.md) | **`LedgerMismatch`** `LedgerMismatchReported` `LedgerReconciliationDidNotRun` | **자동 교정 금지.** 원장 누락인지 잔고 오염인지 사람이 판단한다 |
| [broker-cb-open.md](broker-cb-open.md) | `BrokerCircuitOpen` `ExternalHttpSlow` | 실주문·잔고가 503. 증권사 장애인지 우리 타임아웃인지 구분 |
| [tick-stalled.md](tick-stalled.md) | `TickPipelineStalled` `DltMessagesGrowing` `CandleFlushFailing` | 장중 시세 정지. 게이트웨이 → Kafka → worker → api 순으로 좁힌다 |
| [db-failover.md](db-failover.md) | `ApiErrorBudgetBurn` + `HikariPoolNearExhaustion`/`AllReplicasDown` | Postgres 장애·복구·검증. readiness가 pod를 빼는 동안 할 일 |
| [deploy-rollback.md](deploy-rollback.md) | (배포 직후 아무 알람) | 이미지 되돌리기, 마이그레이션 역호환, 되돌리면 안 되는 것 |
| [search-index.md](search-index.md) | `SearchIndexMappingMismatch` `SearchIndexDltGrowing` `SearchFallbackSustained` | ES는 검색 레이어. DB 폴백으로 살아 있으니 급하지 않되 재색인 절차 |

## 공통 원칙

1. **먼저 대시보드**: Service Health → 해당 도메인. 알람 하나가 아니라 무엇이 같이 아픈지 본다.
2. **완화가 근본 원인 조사보다 먼저**지만, 돈이 걸린 것(원장·주문)은 완화도 사람이 결정한다.
3. **자동 복구 장치가 이미 있는지 확인**: Outbox 5분 재전송, Saga 5분 recoverIncomplete, Lettuce 재연결, HPA, readiness 제외.
   이 장치들이 돌고 있다면 "기다리면서 관찰"이 맞는 대응일 수 있다.
4. 모든 조치는 `#monticker-alerts-page` 스레드에 시각과 함께 남긴다.

## 템플릿

증상 / 영향 범위 / 1차 확인 3단계 / 완화 조치 / 근본 원인 조사 / 에스컬레이션 기준
