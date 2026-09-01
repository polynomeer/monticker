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
- `backfillOnStartup()`은 워커 인스턴스마다(role 무관, 기존 collector들과 동일하게
  role 가드 없음) 기동 시 1회 `SELECT COUNT(*)`를 실행한다 — `candles_1d`가 이미
  채워진 정상 상태에서는 즉시 스킵되므로 비용은 무시할 수준.
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
