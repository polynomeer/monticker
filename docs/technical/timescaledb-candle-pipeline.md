# TimescaleDB 캔들 데이터 파이프라인

> 대상 독자: TimescaleDB를 처음 접하는 시니어 백엔드 엔지니어  
> 범위: price_ticks 수집부터 OHLCV 캔들 제공까지의 전체 경로

---

## 1. 개요

주식 가격 데이터는 전형적인 시계열(time-series) 워크로드다. 특성을 한 줄로 요약하면 **쓰기는 단조 증가하고, 읽기는 시간 범위 스캔**이다.

| 특성 | 설명 |
|------|------|
| 단조 삽입 | 과거 틱은 수정되지 않는다. 새 틱은 항상 최신 시각에 append된다. |
| 높은 카디널리티 | 종목 수 × 초당 틱 수 = 수만 행/분 |
| 범위 쿼리 집중 | `WHERE stock_id = ? AND trade_time BETWEEN ? AND ?` 패턴이 압도적 |
| 오래된 데이터 저가치 | 수개월 전 틱 데이터는 집계 결과(캔들)로 대체 가능 |

일반 PostgreSQL 테이블로도 이 워크로드를 처리할 수 있지만, 데이터가 쌓일수록 범위 스캔 비용이 선형에서 비선형으로 증가한다. TimescaleDB의 hypertable은 내부적으로 시간 축을 기준으로 데이터를 물리 파티션(chunk)으로 분할하여 이 문제를 해소한다.

monticker의 파이프라인은 세 단계로 구성된다.

1. **수집**: Worker가 가격 틱을 생성하고 Redis에 캐시하면서 동시에 `price_ticks` hypertable에 저장한다.
2. **집계**: 분 단위 OHLCV 캔들을 `candles_1m` 테이블에 기록한다. 현재는 메모리 내 집계 방식이며, Continuous Aggregate로 마이그레이션할 수 있다.
3. **조회**: API 서버의 `CandleRepository`가 `candles_1m` / `candles_1d` 테이블을 시간 범위 기준으로 조회한다.

---

## 2. 데이터 파이프라인 아키텍처

```
  [ 외부 시세 / MockPriceGenerator ]
              |
              | GeneratedTick { stockId, price, volume, tradeTime }
              v
  +-----------+-----------+
  |   MarketDataCollector  |  @Scheduled(fixedDelay=1000ms)
  +-----------+-----------+
              |
       +------+------+
       |             |
       v             v
 RedisTickWriter   EventDetector
 (stock:price:*)   (spike/volume)
       |
       | (현재 구현: Redis SET)
       | (예정: DB INSERT → price_ticks)
       v

  +--------------------------+
  |   price_ticks            |  TimescaleDB hypertable
  |  (stock_id, trade_time)  |  chunk interval: 1 day (기본값)
  +--------------------------+
              |
    +---------+---------+
    |                   |
    v                   v
  in-memory          Continuous
  CandleAggregator   Aggregate (예정)
  (분 단위 flush)    cagg_candles_1m
    |
    v
  +--------------------------+
  |   candles_1m             |  hypertable
  |   candles_1d             |  hypertable
  +--------------------------+
              |
              v
  +--------------------------+
  |   CandleRepository       |  Spring JDBC
  |   findCandles(...)       |
  +--------------------------+
              |
              v
       [ REST API / WebSocket ]
```

현재 구현에서 Worker는 틱을 Redis에만 기록한다(`RedisTickWriter`). `price_ticks` hypertable로의 직접 삽입과 캔들 집계는 다음 개발 단계에서 추가될 예정이다. hypertable과 캔들 테이블은 `init-timescaledb.sql`을 통해 이미 구성되어 있다.

---

## 3. hypertable vs 일반 테이블

### 선택 근거

TimescaleDB의 `create_hypertable()`은 논리적으로는 단일 테이블이지만 물리적으로는 시간 구간별 chunk로 분할된다. monticker가 hypertable을 선택한 이유는 다음과 같다.

- **범위 쿼리 pruning**: `WHERE trade_time BETWEEN t1 AND t2` 쿼리가 들어오면 TimescaleDB 플래너가 해당 시간 범위에 속하지 않는 chunk를 물리적으로 제외(chunk exclusion)한다. 1년치 데이터에서 최근 1시간 데이터를 조회할 때 일반 테이블은 전체 인덱스를 탐색하지만, hypertable은 최근 chunk 하나만 접근한다.
- **압축**: 오래된 chunk에 `compress_chunk()`를 적용해 저장 공간을 크게 줄일 수 있다.
- **파티션 단위 DROP**: 보존 기간이 지난 데이터를 `drop_chunks()`로 O(1)에 삭제할 수 있다. 일반 테이블에서 `DELETE`는 vacuum 부하를 유발한다.

### 파티셔닝 동작 방식

```sql
-- infra/docker/init-timescaledb.sql
SELECT create_hypertable('price_ticks', 'trade_time', if_not_exists => TRUE);
SELECT create_hypertable('candles_1m',  'candle_time', if_not_exists => TRUE);
SELECT create_hypertable('candles_1d',  'candle_time', if_not_exists => TRUE);
```

`create_hypertable`은 두 번째 인자로 지정한 타임스탬프 컬럼을 기준으로 chunk를 생성한다. 기본 `chunk_time_interval`은 7일이다. 틱 데이터처럼 밀도가 높은 경우에는 1일로 줄이는 것이 권장된다.

```sql
-- 틱 테이블: chunk 크기를 1일로 조정 (선택 사항)
SELECT set_chunk_time_interval('price_ticks', INTERVAL '1 day');
```

chunk는 `timescaledb_information.chunks` 뷰로 확인할 수 있다. 각 chunk는 내부적으로 `_timescaledb_internal._hyper_N_M_chunk` 형태의 물리 테이블이다. 애플리케이션 코드는 원본 테이블명(`price_ticks`)만 사용하면 되며, 라우팅은 TimescaleDB가 투명하게 처리한다.

---

## 4. in-memory CandleAggregator 설계 (현재 아키텍처 기준)

현재 Worker는 `MockPriceGenerator`가 생성한 틱을 `RedisTickWriter`를 통해 Redis에 기록한다. 분 단위 캔들 집계가 필요한 시점에는 메모리 내 집계 컴포넌트(`CandleAggregator`)를 Worker 파이프라인에 추가하는 방식을 사용할 수 있다.

### 설계 원칙

in-memory 집계는 다음 세 가지 요구사항을 충족해야 한다.

1. **분 단위 버킷**: 각 틱은 `floor(tradeTime, 1 minute)` 기준으로 버킷에 할당된다.
2. **OHLCV 갱신**: 같은 버킷의 틱이 들어올 때마다 high/low를 비교하고, close를 최신 가격으로 갱신한다.
3. **flush 타이밍**: 버킷의 분이 바뀌는 시점, 즉 새 틱의 버킷 시각이 기존 버킷 시각보다 클 때 이전 버킷을 DB에 upsert하고 메모리에서 제거한다.

```
tick(t=09:31:45) → bucket=09:31 → open=100, high=100, low=100, close=100
tick(t=09:31:52) → bucket=09:31 → high=max(100,102)=102, close=102
tick(t=09:32:01) → bucket != 09:31 → flush(09:31) → start bucket=09:32
```

### flush 시 DB 작업

```sql
INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (stock_id, candle_time)
DO UPDATE SET
    high   = GREATEST(candles_1m.high, EXCLUDED.high),
    low    = LEAST(candles_1m.low, EXCLUDED.low),
    close  = EXCLUDED.close,
    volume = candles_1m.volume + EXCLUDED.volume;
```

`ON CONFLICT ... DO UPDATE` (upsert)를 사용하는 이유는 Worker가 재시작되거나 동일 버킷의 캔들이 두 번 flush될 경우 중복 삽입을 안전하게 처리하기 위해서다.

### 메모리 상태의 위험

in-memory 집계는 Worker 프로세스가 비정상 종료되면 현재 분의 캔들이 유실된다. 이를 허용할 수 있는지는 서비스 요구사항에 따라 다르다. 실시간 차트에서 최신 분봉 하나가 누락되는 것이 허용된다면 구현이 단순한 in-memory 방식이 적합하다. 허용되지 않는다면 아래의 Continuous Aggregate 전략을 고려한다.

---

## 5. Continuous Aggregate — TimescaleDB 네이티브 집계

Continuous Aggregate는 TimescaleDB가 제공하는 물리적 집계 뷰다. 일반 PostgreSQL의 Materialized View와 유사하지만, 전체를 재계산하지 않고 새로 추가된 chunk 범위만 증분 갱신(incremental refresh)하는 것이 핵심 차이점이다.

### 생성 방법

```sql
CREATE MATERIALIZED VIEW cagg_candles_1m
WITH (timescaledb.continuous) AS
SELECT
    stock_id,
    time_bucket('1 minute', trade_time)  AS candle_time,
    first(price, trade_time)             AS open,
    max(price)                           AS high,
    min(price)                           AS low,
    last(price, trade_time)              AS close,
    sum(volume)                          AS volume
FROM price_ticks
GROUP BY stock_id, time_bucket('1 minute', trade_time);
```

`first()` / `last()`는 TimescaleDB가 제공하는 집계 함수로, 시간 순서상 첫 번째와 마지막 값을 반환한다. 이 함수들이 없으면 open/close를 별도 서브쿼리 없이 구할 수 없다.

### LATERAL JOIN과의 비교

일반 PostgreSQL에서 OHLCV를 구하려면 LATERAL JOIN 또는 window function이 필요하다.

```sql
-- LATERAL JOIN 방식 (PostgreSQL)
SELECT
    b.candle_time,
    b.stock_id,
    o.price AS open,
    h.high,
    l.low,
    c.price AS close,
    b.volume
FROM (
    SELECT stock_id,
           date_trunc('minute', trade_time) AS candle_time,
           max(price) AS high,
           min(price) AS low,
           sum(volume) AS volume
    FROM price_ticks
    GROUP BY 1, 2
) b,
LATERAL (SELECT price FROM price_ticks
         WHERE stock_id = b.stock_id
           AND date_trunc('minute', trade_time) = b.candle_time
         ORDER BY trade_time ASC LIMIT 1) o,
LATERAL (SELECT price FROM price_ticks
         WHERE stock_id = b.stock_id
           AND date_trunc('minute', trade_time) = b.candle_time
         ORDER BY trade_time DESC LIMIT 1) c;
```

LATERAL JOIN은 매번 원본 테이블을 재스캔하며, 집계 결과를 캐시하지 않는다. 수천만 행에서 실행하면 수 초의 응답 시간이 발생한다. Continuous Aggregate는 이미 계산된 결과를 읽으므로 응답 시간이 밀리초 단위다.

### 자동 갱신 정책

```sql
SELECT add_continuous_aggregate_policy(
    'cagg_candles_1m',
    start_offset => INTERVAL '1 hour',
    end_offset   => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute'
);
```

이 정책은 1분마다 실행되며, `now() - 1 hour`부터 `now() - 1 minute`까지의 범위를 증분 갱신한다. `end_offset`을 1분으로 설정하는 이유는 현재 진행 중인 분의 틱이 아직 완결되지 않았기 때문이다.

---

## 6. CandleRepository의 조회 전략

현재 `CandleRepository`는 `candles_1m`과 `candles_1d` 테이블을 직접 조회한다.

```kotlin
// backend/api/src/main/kotlin/com/monticker/api/marketdata/infrastructure/CandleRepository.kt
fun findCandles(stockId: Long, table: String, from: Instant, to: Instant, limit: Int = 300): List<Candle> {
    val allowed = setOf("candles_1m", "candles_1d")
    require(table in allowed) { "Invalid candle table: $table" }
    // ...
}
```

`table` 파라미터를 화이트리스트(`allowed`)로 검증하여 SQL Injection을 방지한다. 동적 테이블명을 쿼리에 직접 삽입하는 방식이므로 이 검증이 없으면 임의 테이블을 노출할 수 있다.

### Continuous Aggregate 도입 시 fallback 전략

Continuous Aggregate 뷰(`cagg_candles_1m`)를 도입할 경우, 뷰가 아직 갱신되지 않았거나 존재하지 않는 환경(로컬 개발, 테스트)을 고려한 fallback 로직이 필요하다.

```kotlin
fun findCandles(stockId: Long, interval: String, from: Instant, to: Instant): List<Candle> {
    val caggView = "cagg_candles_$interval"
    val fallbackTable = "candles_$interval"

    val sourceTable = if (caggViewExists(caggView)) caggView else fallbackTable

    return jdbc.query(
        "SELECT ... FROM $sourceTable WHERE stock_id = ? AND candle_time BETWEEN ? AND ? ...",
        ...
    )
}

private fun caggViewExists(viewName: String): Boolean {
    val count = jdbc.queryForObject(
        """
        SELECT count(*) FROM timescaledb_information.continuous_aggregates
        WHERE view_name = ?
        """,
        Int::class.java,
        viewName,
    ) ?: 0
    return count > 0
}
```

`timescaledb_information.continuous_aggregates`는 TimescaleDB가 제공하는 메타데이터 뷰다. 이 쿼리를 매 요청마다 실행하면 오버헤드가 발생하므로, 애플리케이션 시작 시 한 번 검사하고 결과를 캐시하는 것이 좋다.

---

## 7. IMMUTABLE 제약 문제

### 문제 상황

Continuous Aggregate 및 파티션 pruning은 내부적으로 시간 버킷 함수가 `IMMUTABLE`이어야 한다는 제약을 따른다. `IMMUTABLE` 함수는 동일 입력에 항상 동일 출력을 반환해야 한다.

`date_trunc('minute', trade_time)`에서 `trade_time`이 `TIMESTAMPTZ`인 경우, 결과는 세션의 `TimeZone` 설정에 따라 달라진다. 따라서 이 표현식은 엄밀하게 `IMMUTABLE`이 아니다.

```sql
-- 이 쿼리는 Continuous Aggregate에서 오류를 유발할 수 있다
SELECT date_trunc('minute', trade_time) FROM price_ticks;
-- ERROR: only immutable functions are supported in continuous aggregates
```

### 해결법: time_bucket 또는 AT TIME ZONE

**방법 1**: TimescaleDB의 `time_bucket()` 함수를 사용한다. 이 함수는 `TIMESTAMPTZ`에 대해 UTC 기준으로 버킷을 계산하며, TimescaleDB가 `IMMUTABLE`로 선언하여 Continuous Aggregate에서 사용할 수 있다.

```sql
-- 권장: time_bucket 사용
time_bucket('1 minute', trade_time)
```

**방법 2**: 명시적으로 타임존을 고정하면 `date_trunc`도 `IMMUTABLE`로 동작한다.

```sql
-- date_trunc에 타임존 명시
date_trunc('minute', trade_time AT TIME ZONE 'UTC')
```

`AT TIME ZONE 'UTC'`는 `TIMESTAMPTZ`를 UTC 기준 `TIMESTAMP`(타임존 없음)로 변환한다. 반환 타입이 `TIMESTAMP`(not `TIMESTAMPTZ`)이므로 컬럼 타입을 `TIMESTAMPTZ`로 유지하려면 다시 `AT TIME ZONE 'UTC'`로 감싸야 한다.

monticker의 `candles_1m.candle_time`은 `TIMESTAMPTZ`이므로 `time_bucket()`을 사용하는 것이 가장 단순하다.

---

## 8. 운영 고려사항

### 초기 데이터 적재 (Historical Backfill)

Continuous Aggregate를 새로 생성했을 때, 이미 존재하는 `price_ticks` 데이터에 대한 집계가 자동으로 수행되지는 않는다. 수동으로 백필이 필요하다.

```sql
CALL refresh_continuous_aggregate(
    'cagg_candles_1m',
    '2024-01-01 00:00:00+00',
    '2024-12-31 23:59:59+00'
);
```

대량의 히스토리 데이터가 있는 경우, 한 번에 전체 범위를 refresh하면 DB 부하가 집중된다. 월 단위로 나누어 순차 실행하거나, 운영 시간 외에 배치로 실행하는 것을 권장한다.

### refresh_policy 설정

자동 갱신 정책의 `schedule_interval`은 데이터 신선도 요구사항과 DB 부하 사이의 트레이드오프다. 실시간 차트가 요구사항이라면 1분이 적합하다. 일봉(`candles_1d`)에 대해서는 1시간 간격으로 충분하다.

```sql
-- 일봉 집계: 1시간 간격 갱신
SELECT add_continuous_aggregate_policy(
    'cagg_candles_1d',
    start_offset => INTERVAL '3 days',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
);
```

### 데이터 보존 정책

`price_ticks`는 장기 보관이 불필요하다. 30일 이상 된 틱 데이터는 캔들로 집계된 후 삭제할 수 있다.

```sql
SELECT add_retention_policy('price_ticks', INTERVAL '30 days');
```

이 정책은 TimescaleDB background worker가 자동으로 실행하며, 해당 chunk를 DROP TABLE 수준으로 제거한다. `DELETE`보다 훨씬 빠르고 bloat이 없다.

### 모니터링

```sql
-- chunk 현황
SELECT * FROM timescaledb_information.chunks
WHERE hypertable_name = 'price_ticks'
ORDER BY range_end DESC
LIMIT 10;

-- Continuous Aggregate 갱신 이력
SELECT * FROM timescaledb_information.job_stats
WHERE job_id IN (
    SELECT job_id FROM timescaledb_information.jobs
    WHERE application_name LIKE 'Refresh Continuous Aggregate%'
);
```

갱신이 지연되거나 실패하면 `job_stats`의 `last_run_status`와 `last_run_duration`을 확인한다.

---

## 9. 한계와 향후 방향

### 현재 한계

| 항목 | 현재 상태 | 영향 |
|------|-----------|------|
| price_ticks DB 저장 | 미구현 (Redis에만 기록) | Continuous Aggregate를 생성해도 집계할 원본 데이터가 없음 |
| in-memory 집계 | 미구현 | 분봉 데이터가 candles_1m에 쌓이지 않음 |
| 캔들 API | candles_1m/1d 직접 조회 | 테이블이 비어 있으면 빈 결과 반환 |
| hypertable chunk interval | 기본값(7일) | 틱 밀도가 높아지면 chunk 크기 조정 필요 |

### 단기 로드맵

1. `PriceTickDbWriter` 구현: Worker에서 `price_ticks` hypertable에 틱을 INSERT하는 컴포넌트 추가.
2. `CandleAggregator` 구현: 메모리 내 분 버킷 집계 및 `candles_1m` upsert.
3. Continuous Aggregate V10 migration: `cagg_candles_1m` 뷰 생성 및 자동 갱신 정책 등록.
4. `CandleRepository` fallback 추가: `cagg_candles_1m` 존재 여부를 시작 시 검사하여 뷰 / 테이블 중 하나를 선택.

### 장기 방향

- **chunk 압축**: 7일 이상 된 chunk에 `compress_chunk()`를 적용하여 스토리지 절감. 압축된 chunk는 append-only이므로, 과거 데이터 수정이 필요하면 압축 해제 후 재압축 절차가 필요하다.
- **공간 파티셔닝**: 종목 수가 매우 많아지면 `stock_id`를 기준으로 space dimension을 추가하여 chunk를 더 잘게 분할할 수 있다.
- **실시간 분봉**: WebSocket으로 현재 진행 중인 분봉의 임시 OHLCV를 메모리에서 직접 스트리밍하고, 분이 완성된 시점에 DB에 확정 기록하는 방식으로 지연 없는 차트를 구현할 수 있다.
