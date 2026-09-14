# Redis 장애 — `RedisFailOpenSustained` · `IdempotencyStoreDown`

## 증상
- `IdempotencyStoreDown` (page): `redis_command_failed_total{op=~"idempotency_.*"}` 2분간 증가. **주문 제출이 503**(`Retry-After: 2`)로 거절된다.
- `RedisFailOpenSustained` (ticket): `policy="open"` 실패가 5분간 10건 초과. 레이트리밋·로그인 실패 카운터·캐시가 **꺼진 채** 서비스 중.
- Data Stores 대시보드 "Redis 명령 지연 max"가 200ms 천장에 붙어 있다.

## 영향 범위 (P0-1, CH-01/CH-02 실측)
| 경로 | 정책 | Redis 죽었을 때 |
|------|------|----------------|
| 주문 멱등성 키 | **fail-closed** | 주문 503 — 돈이 두 번 나가는 것보다 낫다 |
| 레이트리밋(IP·유저) | fail-open | 무제한 — 남용 노출 |
| 로그인 실패 카운터 | fail-open | 브루트포스 잠금 없음 |
| Spring Cache(스크리너 등) | fail-open | DB 직접 조회, 지연 ↑ (CH-01: 스크리너 631ms) |
| 알림 쿨다운(worker) | — | 중복 알림 가능 |
| 시장 요약 | fail-open | 빈 응답 |

API 자체는 죽지 않는다(CH-01 PASS). readiness에 Redis는 없다 — pod가 빠지지 않는다.

## 1차 확인 (3단계)
1. `redis-cli -h <host> PING` / K8s: `kubectl exec deploy/redis -n monticker -- redis-cli PING`. 응답 없음 → 프로세스/네트워크. `LOADING` → 재시작 중 RDB 로드.
2. 지연인가 정지인가: 대시보드 "Redis 명령 지연 max". 200ms 근처에서 실패가 간헐적이면 **지연**(CH-02 시나리오) — 메모리 압박·큰 키·느린 명령(`SLOWLOG GET 10`).
3. 어느 op가 실패하는가: `sum by (op, policy) (increase(redis_command_failed_total[5m]))`. `idempotency_*`만이면 키 스페이스 문제 아님 — 전면 장애.

## 완화
- **정지**: Redis 재시작. Lettuce가 자동 재연결한다(CH-01 MTTR 3초). 수동 개입 불필요. 재기동 후 `redis_command_failed_total` 증가가 멈추는지 2분 관찰.
- **지연**: `INFO memory`에서 `used_memory` vs `maxmemory`, `evicted_keys`. 메모리면 큰 키 정리(`--bigkeys`), 아니면 CPU/네트워크.
- 주문이 503인 동안 **사용자 공지**: "일시적으로 주문을 받을 수 없습니다 — 잠시 후 재시도" (`Retry-After` 2초가 이미 응답에 있다).
- **하지 말 것**: 멱등성을 fail-open으로 바꾸지 않는다. 그건 "주문이 두 번 나가도 된다"는 결정이고 장애 중에 내릴 결정이 아니다.

## 근본 원인 조사
- Redis 로그, `INFO stats`의 `rejected_connections`, `maxclients`.
- 우리 쪽 커넥션 수: api pod × Lettuce 풀. 200ms 타임아웃이 너무 짧은지(장기 p99 기록).
- 재발이면 Redis Sentinel/Cluster(scale-out-plan §6.4).

## 복구 후 검증
- `curl -X POST /api/matching/orders` 200, `redis_command_failed_total` 평탄.
- 레이트리밋 복귀: 같은 IP에서 빠른 반복 요청이 429를 받는지.

## 에스컬레이션
- 주문 503이 **10분** 넘게 지속 → 인시던트 선언, 거래 담당 호출.
- fail-open 상태가 **1시간** 넘으면 보안 담당에 알림(레이트리밋·로그인 잠금 부재).
