# monticker — 시스템 설계 리뷰 (2026-10)

> Read this when: ADR 사이의 상호작용을 건드리는 변경을 할 때, 실거래(BYOK) 경로에 기능을 붙일 때, 또는 다음에
> 무엇을 고칠지 고를 때. 개별 결함 목록은 [resilience-plan.md](resilience-plan.md)·[security-review.md](security-review.md)·
> [validation-hardening-plan.md](validation-hardening-plan.md)에 이미 있다 — 이 문서는 그것들이 다루지 않은
> **ADR과 ADR이 만나는 지점**을 본다.

작성일: 2026-10-04 · 기준 커밋: `315b6f6` · 방법: ADR 001~054 전수 재독 → 교차 사고실험 → 코드 추적으로 판정 → 1차 수정

---

## 1. 설계는 어떻게 진화했나

| 시기 | ADR | 성격 | 평가 |
|------|-----|------|------|
| 기반 | 001–004 | 모듈러 모놀리스, TimescaleDB, `stock_events` 중심, Redis Streams(계획) | 단순하고 맞는 출발점 |
| 패턴 확장 | 005–016 | Kafka+Go+Netty, DLT, 멱등 키, Outbox, K8s, Saga, CQRS, 원장, T+2, MSA 2종 | **패턴이 필요보다 먼저 왔다** — 이 중 Netty·trading-service·quant-engine은 나중에 전부 폐기 |
| 데이터 기능 | 017–022 | 투자자 동향, 펀더멘털, Modulith 경계 정리, 일봉 실시간 upsert | 실용적 |
| 상용화 전환 | 023–037 | BYOK 실주문, 조건부 주문, 리밸런싱, AI 제안, 전략 마켓 | **시뮬레이션용 파이프라인 위에 실거래가 올라갔다** |
| 측정 기반 수축 | 038–054 | 파티션 배정, 토픽 선언, 인메모리 상태, 부하테스트 기본값, 락 전략 실측, 서비스 폐기 | 건강한 교정 — "근거 없는 건 걷어내고 잰다" |

궤적 자체는 좋다. 패턴을 먼저 들이고, 측정한 뒤 걷어냈다. 문제는 **상용화 단계(023~)의 결정들이 그 이전 단계의
전제를 다시 묻지 않았다**는 것이다. 각 ADR은 혼자서는 옳은데, 합치면 깨지는 지점이 생겼다.

## 2. 교차 주제 — 개별 버그가 아니라 패턴

### T1. "실거래에 쓰이는 데이터는 진짜·신선·출처가 확인돼야 한다"는 불변식이 없었다
ADR-030/031은 *표시용으로* 미커버 종목을 Mock 틱으로 채우기로 했고, ADR-032는 *같은 토픽*으로 실주문을 냈다. 틱에는
출처가 없었고, api 와이어의 기본값(`marketStatus="OPEN"`, `generatedAt=now()`)은 필드가 빠진 틱을 "지금 막 생성된
정규장 시세"로 위장했다. 리스크 게이트가 보는 `candles_1m`도 같은 틱으로 만들어진다. → **ADR-055로 수정.**

### T2. "결과 불명(unknown outcome)"이 일급 상태가 아니다
- 브로커 주문 타임아웃이 `REJECTED`로 기록된다(`KisBrokerageClient` `RestClientException` catch). 응답만 유실되고
  실제로는 체결됐을 수 있다 → 사용자 재주문 시 이중 주문.
- 조건부 주문은 `TRIGGERED`로 원자적 클레임 후 브로커 호출 전에 크래시하면 영원히 `TRIGGERED`다.
- 워치룰은 인프라 예외를 삼켜 재시도도 DLT도 없었다(→ 수정).

[ADR-053](decisions/053-payment-idempotency-and-failure-classification.md)이 **결제**에는 이미 "결과 불명" 분류를
도입했다. 같은 원리가 **주문**에는 적용되지 않았다 — 패턴은 레포 안에 있는데 옮겨지지 않았다.

### T3. 동시성 제어 방식이 규칙 없이 섞여 있다
- 조건부 UPDATE(원자): 현금 예약(ADR-052), 조건부 주문 클레임(ADR-032) — 잘 됐다.
- JPA read-modify-write, `@Version` **0건**: 모의주문 취소(→ 수정), 모의 정산 `settle()`, 브로커 자격증명 재발급.
  같은 행(`paper_accounts.cash`)을 한쪽은 JDBC 원자 UPDATE로, 다른 쪽은 엔티티 절대값 저장으로 갱신한다 → lost update.
- 스케줄러 분산 락 없음: 정산·사가 복구·포워드 테스트·Outbox 재발행이 모든 api 레플리카에서 돈다. 정산은 Spring Batch
  JobInstance 유일성이 우연히 막지만 수동 실행 엔드포인트(`runId`)가 그 보호를 우회한다.

### T4. 인메모리 상태와 수평 확장이 각자 결정됐다
ADR-046(디텍터 상태)·ADR-021(캔들 버퍼)·인메모리 CLOB는 각각 단일 프로세스 측정으로 맞는 결정이었다. 그러나
[scale-out-plan](scale-out-plan.md)은 레플리카를 전제하고, 리밸런스 시 상태 이관·flush 훅이 없다. 캔들 upsert가
`volume = volume + EXCLUDED.volume`(가산)이라 재시작·리밸런스 리플레이가 거래량을 부풀린다.

### T5. 시간 모델이 KST 중심이다
`candles_1d`를 모든 시장에서 KST 달력일로 버킷팅한다 — 미국 정규장(22:30–05:00 KST)이 이틀 일봉으로 쪼개진다.
16:00 KST 포워드 테스트는 미국 종목에 대해 반쯤 끝난 세션을 평가한다.

## 3. 사고실험 판정

판정: ✅ HOLDS · ⚠️ PARTIAL · ❌ BREAKS · 🔧 이번 리뷰에서 수정

| # | 사고실험 | 판정 | 근거 |
|---|---------|------|------|
| 1 | 미커버 종목 스탑로스 — Mock 랜덤워크가 트리거를 넘는다 | ❌→🔧 | ADR-055 |
| 2 | N개 api 레플리카가 같은 틱으로 같은 조건부 주문을 평가 | ✅ | 원자적 `UPDATE ... WHERE status='ACTIVE'` |
| 3 | 브로커 주문 응답 타임아웃 | ❌→🔧 | `REJECTED`로 기록, 클라이언트 주문 ID 미보존, 브로커 대조 잡 없음 |
| 4 | `TRIGGERED` 클레임 직후 크래시 | ❌→🔧 | 복구 경로 없음 |
| 5 | 같은 사용자의 동시 실주문 2건(조건부 + 수동) | ❌ | 리스크 게이트 TOCTOU, 시간당 주문 수는 제출 후에 기록 |
| 6 | 실주문 전체 즉시 중단 | ❌→🔧 | 킬 스위치 없음(부팅 시 빈 교체뿐) |
| 7 | 토큰 만료 시점 동시 요청 2건 | ⚠️ | KIS 발급 1회/분 → 두 번째 실패가 `authFailedAt` 기록 → 5분 잠금 |
| 8 | 같은 모의 LIMIT 주문 동시 취소 | ❌→🔧 | 이중 환불. 행 락 + 검증 선행 |
| 9 | 모의 매수 중 kill -9 | ✅ | 단일 트랜잭션 롤백(사가 보상 덕이 아니다 — ADR-011 Note) |
| 10 | 동시 모의 매수가 잔고 초과 | ✅ | ADR-052 원자 UPDATE |
| 11 | 워치룰 이벤트 처리 중 DB 장애 | ❌→🔧 | 예외를 삼켜 재시도·DLT 없음 |
| 12 | api가 며칠 떠 있는 동안 T+2 정산 | ❌→🔧 | 리더가 기동일로 고정(모의·**실거래** 둘 다) |
| 13 | 정산 잡 2회 실행(스케줄 + 수동) | ⚠️ | `settle()` 상태 재확인 없음 → 수수료 이중 차감 가능 |
| 14 | worker-event 2 레플리카, 파티션 이동 | ❌ | 부분 캔들 분할·가산 이중 집계, 디텍터 콜드 EMA 오탐 |
| 15 | worker 재시작 중 리플레이 | ❌ | 가산 upsert로 거래량 이중 집계 |
| 16 | KIS 웹소켓 끊김 | ⚠️ | Mock 대체 없음(커버리지 고정), Redis 최신가 TTL 없음 → 멈춘 가격이 현재가로 보인다 |
| 17 | Kafka 30초 장애 후 복구 | ⚠️→🔧 | 밀린 틱으로 지나간 가격에 발동 — ADR-055 신선도 5s 게이트가 실주문 쪽은 막는다 |
| 18 | 알림 룰 생성·삭제 전파 | ⚠️ | pub/sub + 5분 delta. 쿨다운 키를 기록 전에 잡아 실패 시 10분 유실 |
| 19 | 미국 종목 일봉 | ❌ | KST 버킷 분할 |
| 20 | 모의 LIMIT 주문이 체결되나 | ❌ | 호가창에 저장만 되고 매칭되지 않는다 — 사용자가 취소할 때까지 현금 예약 |

## 4. 이번 리뷰에서 한 것

| 커밋 | 내용 |
|------|------|
| `feat(worker)` · `feat(market-gateway)` · `fix(api)` | **ADR-055** — 틱 출처 태깅, 조건부 주문은 실시세·정규장·5s 이내 틱으로만 발동. 합성 틱마다 하던 DB 조회도 사라짐 |
| `fix(api): prevent double refund…` | 취소 시 `PESSIMISTIC_WRITE` 행 락 + 검증 선행. 실제 Postgres 통합 테스트(락 제거 시 실패 확인) |
| `fix(api): let watch-rule infrastructure failures…` | 룰 단위 격리 유지 + 루프 후 재던짐. 이 삼킴이 가리던 테스트 픽스처 결함(9건) 동반 수정 |
| `fix(api): read settlement due-date…` | 모의·실거래 정산 리더를 `@StepScope` + 잡 파라미터 `date`로 |
| 브랜치 적대적 리뷰 반영 | worker `GeneratedTick` 관대한 리더(별개 Deployment 배포 순서 사고 방지 — ADR-055 초안의 틀린 서술 정정), 출처 필터를 `@Async` 디스패치 전 리스너 조건으로, 죽은 코드 `KisPriceProvider`의 `KIS` 태깅 철회, 워치룰 DLT 로그 정정. 모든 신규 테스트는 변이(가드 제거)로 실패하는 것을 확인 |
| 킬 스위치 리뷰 중 발견 | **관리자 API 권한이 무효였다** — `@EnableMethodSecurity` 부재로 `/api/admin/batch`·`search`가 일반 사용자에게 열려 있었다. URL 규칙 + 메서드 보안 이중화, 필터 체인 테스트 ([security-review C4](security-review.md)) |
| `docs` | ADR-004 Superseded 표기, ADR-011/012/014/029/032 구현 차이 Note, architecture.md 드리프트 12곳, data-model.md |

## 5. 남은 것 — 우선순위

### P0 — 실거래 공개 전 필수 (실제 돈)
1. ✅ **주문 결과 불명 상태(T2)** — [ADR-056](decisions/056-brokerage-order-unknown-outcome.md)(2026-10-05). 의도 선기록
   (`PENDING_SUBMIT`) → 트랜잭션 밖 호출 → 결과 3분류, `UNKNOWN`은 당일 주문 목록 매칭으로 해소(재주문 없음).
   같은 종목·방향 재주문 차단, `brokerage_order_unresolved` Page 알림.
2. ✅ **`TRIGGERED` 리퍼** — ADR-056 §5. 결정적 `co-<id>`로 주문 행을 찾고, 행이 없으면 미전송 확정.
3. ✅ **킬 스위치** — [ADR-057](decisions/057-real-order-kill-switch.md)(2026-10-05). `trading_halts`(전역·증권사·사용자,
   관리자 전용), 주문 준비 트랜잭션에서 캐시 없이 판정, 423. 조건부 주문은 ACTIVE로 일시정지. 런북 [trading-halt](runbooks/trading-halt.md).
4. 🟡 **사용자별 실주문 직렬화** — ADR-056이 일부 해결: 주문 준비(tx1)를 `pg_advisory_xact_lock(userId)`로 직렬화하고,
   `PENDING_SUBMIT` 행이 시간당 주문 수에 바로 잡힌다. 남은 것: 락이 브로커 호출 동안은 풀려 있어 **다른 종목** 동시
   주문은 같은 잔고 스냅샷을 볼 수 있다 → 미체결·불명 주문 금액을 스냅샷 현금에서 차감.
5. **조건부 주문 생성 시 커버리지 확인** — ADR-055 이후 미커버 종목 조건부 주문은 조용히 발동하지 않는다. worker의
   커버리지 집합을 Redis에 게시하고 생성 시 거부/경고.

### P1 — 데이터 정합성
6. 캔들 upsert 멱등화 — 가산 대신 분 단위 재계산 또는 오프셋 워터마크. 리밸런스 시 revoke 훅으로 flush.
7. 모의 정산 `settle()` — JDBC 조건부 UPDATE로(`WHERE status='PENDING'`), 계좌는 상대값 갱신.
8. 스케줄러 분산 락(ShedLock 등) — 정산·사가 복구·포워드 테스트·Outbox 재발행. 수동 실행의 `runId` 우회 제거.
9. 자격증명 재발급 계정 단위 락 + 401/403에만 `authFailedAt`.
10. 디텍터 워밍업 강제(N틱 전 판정 금지), 리밸런스 후 `prev` 초기화.
10a. 정산 리더 페이징 누락 — `status='PENDING'` 필터에 offset 페이징이라, 청크 커밋으로 결과 집합이 줄면 다음 페이지가
    50건씩 건너뛴다(마감일 정산 50건 초과 시 절반이 다음 날로). 정렬에 `id` 타이브레이커도 없다. 키셋(`id > lastId`) 리더로.
10b. ADR-055 신선도에 `tradeTime` 검사 추가(거래소 지연 메시지) — Consequences 참고.

### P2 — 모델
11. 시장별 세션 일자로 `candles_1d` 버킷팅(T5).
12. ADR-011 사가 보상 — 지금은 단일 트랜잭션이라 무해하지만, 외부 호출이 사가에 들어오는 순간 보상을 별도 빈으로.
13. 모의 LIMIT 주문 — 매칭할지(가격 트리거) 아니면 LIMIT을 막을지 결정. 인메모리 CLOB를 계속 둘 거면 레플리카
    간 split-brain 대책 먼저.
14. Redis 최신가에 TTL 또는 `asOf` — 멈춘 가격이 현재가로 보이지 않게.

## 6. ADR 위생에 대한 제안

- 54개 ADR 중 `Superseded`가 0건이었다. 부분 번복은 Note로 잘 관리됐지만(002, 005, 009, 021, 029, 040, 043),
  전면 번복(004)과 "구현이 문서와 다른" 경우(011, 014)는 놓쳤다.
- 새 ADR이 기존 ADR과 **같은 데이터(토픽·테이블)를 다른 목적으로 쓰기 시작할 때** — 예: ADR-032가 ADR-030/031의
  토픽을 실주문에 쓰기 시작한 것 — 원래 ADR의 전제를 Context에서 다시 확인하는 것을 ADR 작성 체크리스트에 넣을 만하다.
  이번 Critical은 정확히 그 지점에서 나왔다.
