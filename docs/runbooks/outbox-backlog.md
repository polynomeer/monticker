# Outbox 적체 — `OutboxBacklog` · `WorkerOutboxBacklog` (ticket)

[ADR-094](../decisions/094-separate-outbox-tables-per-app.md). Outbox는 앱마다 따로다.

| 알람 | 테이블 | 게이지 출처 | 무엇이 늦어지나 |
|------|--------|-------------|----------------|
| `OutboxBacklog` | `public.event_publication` | api `BacklogGauges` (`job="monticker-api"`) | 주문·체결 후속 처리(원장 기록, 알림 발행, 검색 색인 이벤트) |
| `WorkerOutboxBacklog` | `worker_outbox.event_publication` | worker `WorkerOutboxSchemaGuard` (`job=~"monticker-worker.*"`) | 수집 쪽 이벤트(탐지 이벤트, 알림 평가 결과 발행) |

**조건**: 미완료(`completion_date IS NULL`) 100건 초과 **또는** 가장 오래된 미완료가 10분 초과, 5분 지속. 대시보드는 Trading › 정합성과
Capacity › 적체의 Outbox 패널이다. api와 worker가 따로 그려진다.

## 증상
- 이벤트가 커밋됐는데 리스너가 아직 처리하지 않았다. 데이터는 잃지 않는다. 업무 변경과 같은 트랜잭션에서 기록됐기 때문이다. **늦을 뿐이다.**
- 재전송은 양쪽 모두 5분 주기(`OutboxResubmissionConfig`, 1분 넘은 미완료 대상)다. 그래서 짧은 장애 뒤 10분 정도 오래된 행이 보이는 것은 정상 범위다.

## 1차 확인
1. **어느 앱인가**: 알람 이름으로 고른다. 둘 다 울리면 공통 의존성(Kafka, DB)을 먼저 의심한다.
2. **어떤 리스너가 막혔나**:
   ```sql
   -- api는 public, worker는 worker_outbox
   SELECT listener_id, event_type, count(*), min(publication_date)
   FROM worker_outbox.event_publication
   WHERE completion_date IS NULL
   GROUP BY 1, 2 ORDER BY 3 DESC;
   ```
3. **재전송이 돌고 있나**: 로그 `[Outbox] 미완료 이벤트 재전송`(debug)과 리스너 예외. worker라면 `[Outbox] 구 공유 테이블 이관 실패`도 본다.
   이관 실패는 재전송을 막지 않는다.

## 원인별 조치
| 보이는 것 | 원인 | 조치 |
|-----------|------|------|
| 모든 리스너가 고르게 쌓임, Kafka 관련 예외 | Kafka 브로커 장애 | 브로커 복구가 먼저다([tick-stalled.md](tick-stalled.md) Kafka 절). 복구 뒤 5분 안에 재전송이 따라잡는다 |
| 특정 리스너 하나만 쌓임 | 그 리스너의 버그나 외부 의존 장애(ES, 외부 API) | 리스너 예외 로그를 본다. 고친 뒤 배포하면 재전송이 처리한다. **행을 손으로 완료 처리하지 않는다** |
| api 테이블에 `com.monticker.worker.*` 행 | 구버전 worker가 아직 public에 씀(ADR-094 전환기) | 새 worker의 `LegacyOutboxDrain`이 주기마다 옮긴다. 구버전 pod가 남아 있는지 확인 |
| 주문·원장 리스너 행 | 원장 기록 실패 | [ledger-mismatch.md](ledger-mismatch.md) 판별 표로 간다. 재전송 뒤 재대사한다 |

## 에스컬레이션
- 30분 넘게 줄지 않고 계속 늘면 재전송이 실패를 반복하는 것이다. 리스너 담당자를 부른다.
- 주문·원장 리스너가 쌓여 있고 `LedgerMismatch`도 울렸다면 page 급으로 다룬다.
