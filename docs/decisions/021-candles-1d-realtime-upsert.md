# ADR-021: candles_1d 무기록 버그 — CandleAggregator 실시간 upsert로 해결

## Status
Accepted

## Context

`V4__create_market_data.sql`은 `candles_1d` 테이블을 만들지만, 저장소 전체에서 이 테이블에
`INSERT`하는 코드가 없었다. `CandleAggregator`는 `price_ticks`를 `candles_1m`에만 flush하고,
일봉 rollup은 어디에도 구현돼 있지 않다.

그런데 `candles_1d`를 읽는 곳은 여럿이다: `ScreenerRepository`(등락률/급상승·급하락 정렬의
`prevClose`), `AlertEvaluator`(VOLUME_SURGE 룰), `BacktestService`, `RiskController` /
`RiskRuleQueryService`(VaR 추정), `PaperPortfolioQueryService`(샤프비율/변동성). 테이블이
항상 비어 있으니 `ScreenerRepository`의 `prevClose`는 항상 null → `changeRate`는 항상 0 →
스크리너의 등락률 컬럼과 급상승/급하락 정렬이 실질적으로 죽어 있었다.

`V10__create_candle_aggregates.sql`은 `candles_1d_cagg`라는 TimescaleDB Continuous
Aggregate를 만들지만, 이건 `price_ticks`에서 직접 집계하는 별도 뷰이지 `candles_1d`
테이블을 채우는 게 아니다. 게다가 이 뷰는 `price_ticks`가 이미 하이퍼테이블일 때만
조건부로 생성되는데, 하이퍼테이블 전환 스크립트(`infra/docker/init-timescaledb.sql`)는
`docker-compose.yml`에도 `backend-ci.yml`에도 어디서도 실행되지 않는다 — 즉
`candles_1d_cagg`는 로컬 개발/CI/현재 docker-compose 배포 어디에서도 실제로 생성된 적이
없는, 존재하지 않는 것과 같은 오브젝트다. TimescaleDB CAgg를 정식 해법으로 삼기엔
이 프로젝트에서 하이퍼테이블 가용성 자체가 검증되지 않았다.

후보:

**A) 워커에 별도 EOD 배치 job 추가** (`StockFundamentalsCollector`류 패턴 — 매일 장마감 후
`candles_1m`을 그룹핑해 `candles_1d`에 적재)

**B) `CandleAggregator.flush()`에서 `candles_1m`과 함께 `candles_1d`도 실시간 upsert**

A안을 먼저 검토했으나 `ScreenerRepository`의 쿼리 형태를 보면 성립하지 않는다:

```sql
LEFT JOIN LATERAL (
    SELECT close FROM candles_1d
    WHERE stock_id = s.id
    ORDER BY candle_time DESC LIMIT 1 OFFSET 1     -- prevClose
) prev ON true
```

`OFFSET 1`은 "가장 최근 행(OFFSET 0)은 진행 중인 오늘, 그 앞(OFFSET 1)이 확정된 전일 종가"를
전제한다. 장마감 후 하루 한 번만 적재하는 배치로는 장중 내내 `candles_1d`에 "오늘" 행이
없으므로 OFFSET 0이 어제, OFFSET 1이 그저께가 돼 등락률이 하루 밀려 계산된다 — 버그를
"장중엔 여전히 틀리고 장마감 후에만 우연히 맞는" 상태로 바꿀 뿐 근본 해결이 아니다.

## Decision

**B안** — `candles_1m`을 flush할 때마다 같은 트랜잭션에서 KST 달력일로 버킷팅한
`candles_1d` 행도 upsert한다 (`CandleAggregator.kt`).

```kotlin
val dayStart = c.minute.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant()
jdbc.update("""
    INSERT INTO candles_1d (stock_id, candle_time, open, high, low, close, volume)
    VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (stock_id, candle_time) DO UPDATE SET
        high   = GREATEST(candles_1d.high,  EXCLUDED.high),
        low    = LEAST(candles_1d.low,   EXCLUDED.low),
        close  = EXCLUDED.close,
        volume = candles_1d.volume + EXCLUDED.volume
    """, ...)
```

`open`은 `DO UPDATE SET`에 포함하지 않으므로 그날 첫 flush에서 들어간 값이 그대로 유지된다.
`high`/`low`/`volume`은 분봉과 동일한 병합 로직(`GREATEST`/`LEAST`/누적합)을 하루 단위로
그대로 재사용한다. 결과적으로 장이 열려 있는 동안 "오늘" 행이 매분 갱신되며 살아있고,
전일 행은 이미 확정된 채 그대로 남아 있어 `OFFSET 1` 조회가 정확히 의도대로 동작한다.

기존에 이미 쌓여 있던 `candles_1m` 이력(이 fix 배포 전부터 운영 중이었다면)을 놓치지 않기
위해, `candles_1d`가 비어 있을 때만 1회 실행되는 `backfillOnStartup()`
(`@Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)`, `StockMasterCollector`와
동일한 부트스트랩 패턴)을 추가해 `candles_1m` 전체를 KST 달력일로 `GROUP BY`한 뒤
`candles_1d`에 한 번에 채운다.

TimescaleDB CAgg(`candles_1d_cagg`)는 그대로 두되 이번 fix에서 의존하지 않는다 — 현재
아무 환경에서도 활성화되지 않으므로 죽은 코드에 가깝고, 향후 하이퍼테이블 전환을 실제로
자동화하게 되면 재검토한다.

## Reasons

- `ScreenerRepository`의 `OFFSET 1` 쿼리가 요구하는 "오늘은 진행 중, 전일은 확정" 모양을
  정확히 만족하는 건 실시간 upsert뿐이다. EOD 배치는 장중 내내 하루 밀린 값을 준다.
- `CandleAggregator`는 이미 매 tick마다 호출되고 있어 추가 스케줄러·락·워커 순회 없이
  기존 flush 경로에 SQL 한 문장만 더하면 된다 — `StockFundamentalsCollector`류처럼 KRX
  전 종목을 순회하는 별도 배치를 새로 만들 필요가 없다.
- 부수 효과로 `AlertEvaluator`의 VOLUME_SURGE 룰도 함께 고쳐진다 — 그 쿼리도 "오늘 행이
  이미 존재한다"를 전제로 `candles_1d`를 self-join하고 있었다.
- TimescaleDB CAgg 경로는 이 저장소에서 하이퍼테이블 전환이 어떤 환경에서도 실행되지
  않고 있어(`init-timescaledb.sql`이 compose/CI 어디에도 연결 안 됨) 지금 그 위에 기능을
  얹는 건 검증되지 않은 전제 위에 쌓는 것과 같다.

## Consequences

- `candles_1m` flush마다 INSERT가 하나 더 늘어난다(분당 종목 수만큼). 분봉 upsert와
  같은 카디널리티라 부하 증가는 미미하다고 판단했다.
- `candles_1d`의 "오늘" 행은 장중에는 해당 종목의 마지막 완성된 1분봉 시점까지의
  OHLCV이지 진짜 당일 최종 종가가 아니다 — 장중 조회 시점에 따라 `close`가 계속
  바뀐다. 스크리너/알림처럼 "현재가 대비 실시간 등락"이 목적인 곳엔 정확히 맞는
  의미이지만, "당일 확정 종가"를 기대하는 배치성 분석에 그대로 갖다 쓰면 오해할 수
  있다.
- `backfillOnStartup()`은 워커 인스턴스마다(role 무관) 기동 시 1회 `SELECT COUNT(*)`를
  실행한다 — `candles_1d`가 이미 채워진 정상 상태에서는 즉시 스킵되므로 비용은 무시할
  수준. (2026-09-01 후속 수정으로 `@DistributedLock`을 추가해 여러 프로세스가 동시에
  기동해도 실제 백필은 하나만 실행되도록 했다 — 아래 참고.)
- `candles_1d_cagg`(TimescaleDB CAgg)는 여전히 어떤 환경에서도 생성되지 않는 죽은
  코드로 남는다 — 이번 fix로 해결한 문제와 별개이므로 정리는 스코프 밖으로 미룬다.

## Revisit When

- `price_ticks`/`candles_1m`/`candles_1d`의 실제 TimescaleDB 하이퍼테이블 전환이
  배포 파이프라인에 자동으로 연결되는 시점이 오면, `candles_1d_cagg`를 살아있는
  경로로 승격할지, 아니면 이번 실시간 upsert 방식과 중복되므로 완전히 제거할지
  재검토한다.
- "당일 확정 종가"가 별도로 필요한 기능(예: 정산/결제 기준가)이 생기면, 장마감
  스냅샷을 `candles_1d`와 별개 컬럼/테이블로 분리하는 것을 고려한다 — 지금처럼
  같은 행을 장중 내내 덮어쓰는 구조로는 "그날 15:30 시점 종가"를 사후에 복원할
  수 없다.

## 후속 수정 (2026-09-01)

최초 구현 리뷰에서 8건이 추가로 제기됐다. 조사 결과 5건은 실재하는 버그였고, 3건은
사용자가 짚은 그대로는 재현되지 않았지만 조사 중 실재하는 인접 문제를 새로 발견했다.
새 결정이 아니라 위 Decision을 견고하게 만드는 구현 보강이라 별도 ADR 번호는 쓰지 않는다.

**고친 것**:
- **백테스트/VaR/샤프비율이 미확정 "오늘" 행을 흡수** — `BacktestService`/
  `RiskRuleQueryService`/`PaperPortfolioQueryService`(전부 `backend/api`) 전부
  `candle_time < todayStartKst` 조건을 추가해 장중 계속 바뀌는 오늘 행을 제외했다.
- **`AlertEvaluator`의 VOLUME_SURGE는 "편향"이 아니라 SQL 자체가 무효했다** —
  비집계 컬럼(`c.volume`)과 집계(`AVG(c2.volume)`)를 GROUP BY 없이 섞어 Postgres가
  매번 예외를 던졌고, `processAlert()`의 바깥 try/catch가 조용히 삼켜서 **이 규칙은
  candles_1d가 비어있던 이전부터도, 채워진 이후로도 한 번도 발동한 적이 없었다**.
  독립된 스칼라 서브쿼리 두 개로 재작성해 유효한 SQL로 고쳤다. 기존 단위테스트는
  `jdbc.queryForMap`을 직접 mock해서 이 문제를 못 잡았던 것도 확인해, 실제 Postgres에
  SQL을 실행하는 `AlertEvaluatorIntegrationTest`를 추가했다. 장 초반 `today_vol`이
  구조적으로 낮게 나오는 편향은 시간대별 정규화가 필요한 별도 기능이라 이번엔 고치지
  않고 코드 주석으로만 남겼다.
- **`CandleAggregator.flush()`에 트랜잭션 경계가 없었다** — `candles_1m`/`candles_1d`
  upsert 두 번이 각자 auto-commit이라, 그 사이에 프로세스가 죽으면 두 테이블이 영영
  어긋날 수 있었다. `PlatformTransactionManager` + `TransactionTemplate`으로 두 upsert를
  하나의 트랜잭션으로 묶었다.
- **`flush()`의 두 upsert 블록이 테이블명만 다르고 완전히 중복** — `upsertCandle()`
  헬퍼로 통합했다.
- **`backfillOnStartup()`의 `array_agg(...)[1]`이 비효율적** — 그룹당 전체 배열을
  메모리에 구성한 뒤 첫/마지막 원소만 취하는 방식이었다. `DISTINCT ON`으로 첫/마지막
  값만 스트리밍으로 뽑는 CTE 세 개(day_bounds/day_open/day_close)로 교체했다.
- **`backfillOnStartup()`에 role/leader 가드가 없었다** — docker-compose의 `msa`
  프로필에서 `worker-market`/`worker-event`/`worker-alert` 3개 프로세스가 기동 시
  동시에 같은 무거운 백필 집계 쿼리를 중복 실행할 수 있었다(데이터 손상은 없었지만
  자원 낭비 + 락 경합). `StockFundamentalsCollector`/`InvestorTrendCollector`가 이미
  쓰는 `@DistributedLock`(Redis SETNX 기반)을 재사용해 하나만 실행되게 했다.
- **종료 시(`@PreDestroy`) 훅이 없었다** — 배포·재시작마다 누적 중이던 마지막
  분봉/일봉이 통째로 유실되고 있었다(사용자가 지적한 "flushAll()로 인한 볼륨
  이중 카운트"를 조사하다 발견 — `flushAll()`은 프로덕션 코드 어디서도 호출되지
  않아 이중 카운트 자체는 재현되지 않았다). `@PreDestroy fun onShutdown() = flushAll()`
  추가.
- **`state: MutableMap`을 `ConcurrentHashMap`으로 교체** — 현재 설정(Kafka 리스너
  concurrency=1)에서는 동시성 레이스가 재현되지 않지만, 향후 설정 변경에 대비한
  저비용 안전장치.

**스코프 밖으로 남긴 것**:
- `BacktestService`/`RiskRuleQueryService`/`PaperPortfolioQueryService`가
  `backend/quant-engine`·`backend/trading-service`에도 동일 이름으로 존재하는데
  **api 버전과 이미 갈라져 있다**(quant-engine의 BacktestService는 날짜범위 검증 자체가
  빠져 있고, trading-service의 PaperPortfolioQueryService는 raw SQL이 아니라 JPA
  repository 기반) — 각각 개별 대조·수정이 필요해 별도 태스크로 분리했다.
- MSA 프로필에서 `worker-market`/`worker-event`/`worker-alert` 3개 프로세스가 전부
  같은 Kafka 컨슈머 그룹으로 `TickKafkaConsumer`를 동시 실행 중이라(`tick.consumer`
  기본값이 어디서나 `legacy`), 파티션 리밸런스 시 진행 중이던 캔들이 유실될 수 있는
  문제를 조사 중 발견했다 — `ConsumerRebalanceListener`/체크포인팅이 필요한 아키텍처
  작업이라 버그 수정 스코프를 벗어나 별도 태스크로 분리했다. → [ADR-022](022-tick-consumer-msa-role-gating.md)에서
  해결: 조사 결과 세 role 중 실제로 `market.ticks`를 소비해야 하는 건 `role=event`
  하나뿐이라, 리밸런스 리스너 대신 활성화 조건을 좁혀 중복 소비 자체를 제거했다.
- VOLUME_SURGE의 시간대별 정규화(위 참고).

## 후속 수정 2 (2026-09-01) — quant-engine·trading-service 포크본 대조

위 "스코프 밖으로 남긴 것"에서 분리했던 `backend/quant-engine`·`backend/trading-service`의
동일 이름 서비스를 개별 대조해 `candle_time < todayStartKst` 필터를 반영했다. 두 모듈은
공유 라이브러리가 아니라 완전히 분기된 포크본이라 각 파일의 실제 구현에 맞춰 개별 수정했다.

**quant-engine — `BacktestService.kt`**:
- 예상보다 갈라진 정도가 컸다. `require(...)` 날짜범위 검증뿐 아니라, 쿼리 자체에
  `candle_time >= ? AND candle_time <= ?` range 필터가 통째로 빠져 있었다 — `BacktestRequest`가
  `fromDate`/`toDate`를 받긴 하지만 실제 SQL에서는 전혀 쓰이지 않고 종목의 전체 이력을
  그대로 로드하고 있었다. 이건 "의도적 단순화"가 아니라 사용자가 지정한 기간이 조용히
  무시되는 별도의 선행 버그로 판단해, api 버전과 동일하게 range 필터 + 검증
  (`toDate - fromDate ≤ 2년`, `toDate ≥ fromDate`) + `candle_time < todayStartKst`를
  모두 복원했다.

**trading-service — `RiskRuleQueryService.kt`**: api 버전과 동일한 구조(raw
`jdbc.queryForList`)라 VaR 쿼리에 `candle_time < todayStartKst` 조건만 추가하면 됐다.

**trading-service — `PaperPortfolioQueryService.kt`**: 사전 조사 단계에서는 `tradeRepo`(JPA)를
쓰는 걸 보고 candles_1d 읽기도 JPQL을 거칠 거라 추정했으나, 실제로는 `tradeRepo`는
`paper_trades`/보유종목 집계에만 쓰이고 `getRiskMetrics()`의 candles_1d 읽기는 api 버전과
동일하게 raw `jdbc.query`였다. 즉 JPA/raw SQL 분기는 candles_1d와 무관 — 다른 세 파일과
동일한 방식으로 `AND candle_time < ?`만 추가했다.

**trading-service — `RiskRuleQueryService.kt`의 별도 사본은 없음. 대신 새로 발견한
`RiskController.kt`**: api에는 없는 `getCurrentExposure()` 엔드포인트가 `RiskController.kt`
자체에 `estimatedVaR` 계산용 raw candles_1d 쿼리를 중복 보유하고 있었다(`RiskRuleQueryService`와
로직이 거의 동일하지만 별도 코드 경로) — 요청 범위를 벗어난 세 번째 파일 검토 지시에 따라
확인했고, 동일한 미확정 "오늘" 행 버그가 있어 같이 고쳤다.

quant-engine 쪽에는 `RiskRuleQueryService`/`PaperPortfolioQueryService` 동일 이름 파일이
없고(둘 다 trading-service에만 존재), trading-service 쪽에는 `BacktestService` 동일 이름
파일이 없다 — 세 서비스가 두 모듈에 정확히 나뉘어 존재해 대조 범위가 명확했다. 두 모듈
전체를 `grep candles_1d`로 재확인해 위 4개 파일 외에 candles_1d를 읽는 곳이 없음을 확인했다.

두 모듈 모두 `./gradlew compileKotlin`, `./gradlew test` 통과 확인.
