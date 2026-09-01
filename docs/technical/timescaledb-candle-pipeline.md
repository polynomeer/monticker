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

1. **수집**: Worker가 가격 틱을 생성해 Redis에 캐시한다.
2. **집계**: Worker의 `CandleAggregator`가 틱을 분 단위로 메모리에서 묶어 `candles_1m`에 upsert한다.
3. **조회**: API 서버의 `CandleRepository`가 `candles_1m` / `candles_1d` 테이블을 시간 범위 기준으로 조회한다.

> **현재 상태 요약**: `price_ticks` / `candles_1m` / `candles_1d`는 모두 `V4__create_market_data.sql`이 만든 일반 PostgreSQL 테이블이다. TimescaleDB hypertable 전환 스크립트(`infra/docker/init-timescaledb.sql`)는 어떤 환경의 `docker-compose.yml`/CI에서도 실행되지 않으므로, 아래에서 "hypertable"이라고 부르는 내용은 **아직 활성화되지 않은 설계**다. 무엇이 실제로 동작하는지는 9장을 먼저 참고한다.

---

## 2. 데이터 파이프라인 아키텍처

```
  [ MockPriceGenerator / Go market-gateway ]
              |
              | GeneratedTick { stockId, price, volume, tradeTime }
              v
     Kafka topic: market.ticks
              |
              v
  +--------------------------------+
  |  TickKafkaConsumer.onTick()     |  (tick.consumer=legacy, 기본값)
  |  또는 TickPipelineConfig         |  (tick.consumer=integration,
  |  (Spring Integration EIP)       |   Spring Integration 대체 경로)
  +--------------------------------+
              |
       +------+------+---------------+
       |             |               |
       v             v               v
 RedisTickWriter  CandleAggregator  EventDetector
 (stock:price:*)  .onTick(tick)     (spike/volume)
                       |
                       | 분(minute)이 바뀌는 시점에 flush()
                       v
              +--------------------+
              |   candles_1m       |  일반 PostgreSQL 테이블
              +--------------------+

  [ PriceTickDbWriter ]  ← 어디서도 호출되지 않음 (dead code, 아래 참고)
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

**실제로 동작하는 것과 아닌 것**

- `RedisTickWriter`: 매 틱마다 Redis에 최신가를 기록한다 — 동작 중.
- `CandleAggregator`: 매 틱마다 `onTick()`이 호출되어 분 단위로 OHLCV를 메모리에서 집계하고, 분이 바뀌면 `candles_1m`에 upsert한다 — 동작 중 (4장 참고).
- `PriceTickDbWriter`(`price_ticks` INSERT 담당): 클래스는 구현되어 있지만 `TickKafkaConsumer`/`TickPipelineConfig`/`MarketTickScheduler` 어디에서도 주입·호출되지 않는다. 다이어그램에서 틱 처리 흐름과 분리해 별도로 표시한 이유가 이것이다 — **실질적으로 dead code이며, `price_ticks` 테이블에는 아무것도 적재되지 않는다.**
- `candles_1d`: 이 테이블에 INSERT하는 코드가 이 브랜치에는 없다. `CandleRepository.findCandles()`가 `candles_1d`를 조회 대상으로 허용하고 있지만, 채워주는 쓰기 경로가 없으므로 실제로는 항상 빈 결과를 반환한다. `candles_1d`를 `CandleAggregator.flush()`에서 실시간 upsert하는 설계는 별도 브랜치(`claude/sleepy-dhawan-48ddcb`)에서 진행 중이며 아직 이 브랜치에 병합되지 않았다 (8장, 9장 참고).

---

## 3. hypertable vs 일반 테이블

> **미가동 상태**: 이 장은 TimescaleDB hypertable의 일반적인 동작 원리를 설명하는 배경 지식이다. `price_ticks`/`candles_1m`/`candles_1d`를 hypertable로 전환하는 `infra/docker/init-timescaledb.sql`은 어떤 `docker-compose.yml`/CI 환경에서도 실행되지 않으므로, 현재 이 테이블들은 실제로는 그냥 일반 PostgreSQL 테이블이다(9장 참고). 아래 `create_hypertable` 호출은 실행되지 않는다.

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

## 4. in-memory CandleAggregator 구현 (현재 코드 기준)

`CandleAggregator`(`backend/worker/src/main/kotlin/com/monticker/worker/marketdata/CandleAggregator.kt`)는 이미 구현되어 있고 틱 파이프라인에 실제로 연결되어 있다. `TickKafkaConsumer.onTick()`(기본 경로)과 `TickPipelineConfig`의 `candleAggregateFlow`(Spring Integration 대체 경로) 양쪽에서 매 틱마다 `candleAggregator.onTick(tick)`을 호출한다.

### 동작 방식

`state: MutableMap<Long, CandleState>`가 **종목(stockId)당 "현재 진행 중인 분봉 하나"**만 메모리에 들고 있는다 — `(stockId, minute)` 쌍이 아니라 `stockId` 하나로 키가 잡혀 있다는 점이 중요하다.

1. **분 단위 버킷**: 틱의 `tradeTime`을 `truncatedTo(ChronoUnit.MINUTES)`로 분 단위로 내림한 값이 그 종목의 버킷 키다.
2. **OHLCV 갱신**: 같은 버킷에 틱이 들어올 때마다 `high = max(high, price)`, `low = min(low, price)`, `close = price`, `volume += tick.volume`으로 갱신한다.
3. **flush 타이밍**: 그 종목에 새 틱이 들어왔는데 버킷의 분이 이전과 다르면, 그제서야 이전 버킷을 `flush()`하고 새 버킷을 연다. **별도의 타이머나 스케줄러는 없다** — flush는 오직 "다음 틱이 도착했을 때"에만 일어난다.

```
tick(t=09:31:45) → bucket=09:31 → open=100, high=100, low=100, close=100
tick(t=09:31:52) → bucket=09:31 → high=max(100,102)=102, close=102
tick(t=09:32:01) → bucket != 09:31 → flush(09:31) → start bucket=09:32
```

### flush 시 DB 작업

```sql
-- CandleAggregator.flush()
INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (stock_id, candle_time) DO UPDATE SET
    high   = GREATEST(candles_1m.high, EXCLUDED.high),
    low    = LEAST(candles_1m.low, EXCLUDED.low),
    close  = EXCLUDED.close,
    volume = candles_1m.volume + EXCLUDED.volume
```

`ON CONFLICT ... DO UPDATE` (upsert)를 사용하는 이유는 Worker가 재시작되거나 동일 버킷의 캔들이 두 번 flush될 경우 중복 삽입을 안전하게 처리하기 위해서다. `flush()`는 `candles_1m`만 갱신한다 — `candles_1d`에 대한 INSERT는 없다(2장 참고).

### 메모리 상태의 위험

- **Worker 재시작 시 유실**: `flush()`는 다음 틱이 들어올 때만 호출되므로, 진행 중인 분봉은 메모리에만 있다가 Worker가 재시작되면 사라진다. `flushAll()` 메서드가 정의되어 있지만 shutdown hook이나 스케줄러 어디에서도 호출되지 않아 — 이것도 사실상 미사용 코드다. 재시작 시점의 마지막 분봉 하나가 유실되는 것은 그대로다.
- **거래 정지·조용한 종목의 지연 flush**: 특정 종목의 틱이 한동안 들어오지 않으면(장중 거래 정지 등), 그 종목의 마지막 버킷은 다음 틱이 들어올 때까지 `candles_1m`에 반영되지 않는다. 실시간 차트에서 "방금 갱신된 분봉 하나가 누락"되는 정도는 허용 가능한 트레이드오프로 보고 있다.

---

## 5. Continuous Aggregate — TimescaleDB 네이티브 집계

> **미가동 상태**: 이 장이 설명하는 Continuous Aggregate(`candles_1m_cagg`, `candles_1d_cagg`)는 `V10__create_candle_aggregates.sql`에 실제로 정의되어 있지만, 그 마이그레이션은 `price_ticks`가 이미 TimescaleDB hypertable인 경우에만 뷰를 생성하도록 조건부(`IF EXISTS ... timescaledb_information.hypertables`)로 작성되어 있다. 3장에서 설명했듯 hypertable 전환 자체가 어떤 환경에서도 실행되지 않으므로, 이 조건은 항상 거짓이고 두 CAgg 뷰는 실제로 한 번도 생성된 적이 없다. `candles_1m`/`candles_1d`를 채우는 것은 4장의 `CandleAggregator` 실시간 upsert뿐이다. 아래 내용은 향후 hypertable 전환을 실제로 자동화할 때 참고할 설계 자료로 남겨둔다.

### 생성 방법 (V10 마이그레이션 기준, 조건이 참일 때만 실행됨)

```sql
CREATE MATERIALIZED VIEW candles_1m_cagg
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
    'candles_1m_cagg',
    start_offset => INTERVAL '2 hours',
    end_offset   => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute'
);
```

이 정책은 1분마다 실행되며, `now() - 1 hour`부터 `now() - 1 minute`까지의 범위를 증분 갱신한다. `end_offset`을 1분으로 설정하는 이유는 현재 진행 중인 분의 틱이 아직 완결되지 않았기 때문이다.

---

## 6. CandleRepository의 조회 전략

`CandleRepository`는 `candles_1m`과 `candles_1d` 테이블을 직접 조회한다. Continuous Aggregate 뷰가 실제로 생성된 적이 없으므로(5장) 이것이 유일한 조회 경로다 — fallback 분기 자체가 존재하지 않는다.

```kotlin
// backend/api/src/main/kotlin/com/monticker/api/marketdata/infrastructure/CandleRepository.kt
fun findCandles(stockId: Long, table: String, from: Instant, to: Instant, limit: Int = 300): List<Candle> {
    val allowed = setOf("candles_1m", "candles_1d")
    require(table in allowed) { "Invalid candle table: $table" }
    // ...
}
```

`table` 파라미터를 화이트리스트(`allowed`)로 검증하여 SQL Injection을 방지한다. 동적 테이블명을 쿼리에 직접 삽입하는 방식이므로 이 검증이 없으면 임의 테이블을 노출할 수 있다.

앞서 언급했듯 `candles_1d`는 현재 아무도 쓰지 않으므로, `table = "candles_1d"`로 조회하면 항상 빈 리스트가 반환된다.

### (참고) Continuous Aggregate 도입 시 필요할 fallback 전략

`candles_1m_cagg`/`candles_1d_cagg`가 실제로 생성되기 시작하면, 뷰가 아직 없거나 아직 refresh되지 않은 환경(로컬 개발, 테스트)을 고려한 fallback 로직이 `CandleRepository`에 필요해진다. **아래 코드는 설계 스케치일 뿐 구현된 적이 없다** — `timescaledb_information.continuous_aggregates`를 조회해 뷰 존재 여부로 분기하는 방식을 참고용으로만 남겨둔다.

```kotlin
// 미구현 — 설계 참고용
fun findCandles(stockId: Long, interval: String, from: Instant, to: Instant): List<Candle> {
    val caggView = "candles_${interval}_cagg"
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

### candles_1d 백필 — 진행 중인 작업 (별도 브랜치, 미병합)

이 브랜치에는 `candles_1d`를 채우는 코드가 전혀 없다(2장, 4장 참고). `candles_1d`를 `CandleAggregator.flush()`에서 `candles_1m`과 함께 실시간 upsert하고, 앱 기동 시 `candles_1d`가 비어 있으면 기존 `candles_1m` 이력을 KST 달력일 기준으로 묶어 한 번에 채우는(`backfillOnStartup()`) 설계가 다른 작업 브랜치(`claude/sleepy-dhawan-48ddcb`)에 구현되어 있으나, 이 브랜치에는 아직 병합되지 않았다. 이 방식은 TimescaleDB Continuous Aggregate와 무관하며, `ScreenerRepository`의 전일 종가(`prevClose`) 조회가 "가장 최근 행 = 진행 중인 오늘, 그 다음 행 = 확정된 전일 종가"를 전제하기 때문에 장마감 후 1회 배치보다 실시간 upsert가 필요하다는 것이 핵심 근거다. 이 결정이 이 브랜치에 병합되면 관련 ADR을 이 저장소의 ADR 번호 규칙에 맞춰 등록해야 한다(현재 `ADR-020`은 스톡 밸류에이션 스코어에 이미 배정되어 있어 번호 충돌이 있다).

### 초기 데이터 적재 (Historical Backfill) — TimescaleDB CAgg 기준, 미가동

아래는 Continuous Aggregate(5장)가 실제로 활성화된 이후에나 필요한 절차다. Continuous Aggregate를 새로 생성했을 때, 이미 존재하는 `price_ticks` 데이터에 대한 집계가 자동으로 수행되지는 않는다. 수동으로 백필이 필요하다.

```sql
CALL refresh_continuous_aggregate(
    'candles_1m_cagg',
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
    'candles_1d_cagg',
    start_offset => INTERVAL '3 days',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
);
```

### 데이터 보존 정책 — hypertable 전환 이후에나 적용 가능, 미가동

`price_ticks`는 장기 보관이 불필요하다. 30일 이상 된 틱 데이터는 캔들로 집계된 후 삭제할 수 있다. 단 `add_retention_policy`는 hypertable에만 적용되는 TimescaleDB 기능이므로, 3장에서 설명한 hypertable 전환이 실행되기 전까지는 이 정책 자체를 등록할 수 없다.

```sql
SELECT add_retention_policy('price_ticks', INTERVAL '30 days');
```

이 정책은 TimescaleDB background worker가 자동으로 실행하며, 해당 chunk를 DROP TABLE 수준으로 제거한다. `DELETE`보다 훨씬 빠르고 bloat이 없다.

### 모니터링 — hypertable/CAgg 전제, 미가동

아래 쿼리들은 `timescaledb_information.chunks`/`jobs`처럼 hypertable·Continuous Aggregate가 실제로 존재할 때만 의미 있는 결과를 반환한다. 지금은 두 조건 모두 해당하지 않으므로 참고 자료로만 남겨둔다.

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

### 현재 상태 (이 브랜치 기준)

| 항목 | 현재 상태 | 근거 | 영향 |
|------|-----------|------|------|
| `price_ticks` DB 저장 | **미구현** — `PriceTickDbWriter`는 존재하지만 어디서도 호출되지 않는 dead code | `PriceTickDbWriter.kt`; `TickKafkaConsumer`/`TickPipelineConfig`에서 미주입 | `price_ticks` 테이블은 항상 비어 있음. 틱 원본은 Redis에만 존재 |
| `candles_1m` 집계 | **구현되어 동작 중** — `CandleAggregator`가 매 틱마다 메모리에서 분 버킷을 갱신하고, 분이 바뀌면 upsert | `CandleAggregator.kt`; `TickKafkaConsumer.onTick()` / `TickPipelineConfig.candleAggregateFlow` | 실시간 차트가 `candles_1m` 기준으로 정상 동작 |
| `candles_1d` 집계 | **미구현** (이 브랜치 기준) — 어디에도 INSERT 경로 없음. 실시간 upsert + 기동 시 백필 설계가 별도 브랜치(`claude/sleepy-dhawan-48ddcb`, 미병합)에 있음 | 2장, 8장 참고 | `candles_1d` 조회는 항상 빈 결과. 스크리너 등락률(`prevClose`), `AlertEvaluator`의 VOLUME_SURGE 등 `candles_1d`를 읽는 기능이 죽어 있을 수 있음 |
| TimescaleDB hypertable 전환 | **미가동** — `init-timescaledb.sql`이 `docker-compose.yml`/CI 어디에도 연결되지 않음 | 3장 | `price_ticks`/`candles_1m`/`candles_1d`는 실제로는 일반 PostgreSQL 테이블. chunk pruning/압축/보존 정책 모두 미적용 |
| Continuous Aggregate | **미가동** — `V10__create_candle_aggregates.sql`이 hypertable 존재를 전제로 조건부 생성하는데, 그 전제가 항상 거짓 | 5장 | `candles_1m_cagg`/`candles_1d_cagg` 뷰가 생성된 적이 없음 |
| `CandleRepository` CAgg fallback | **설계만 존재, 미구현** | 6장 | `candles_1m`/`candles_1d` 테이블을 항상 직접 조회 |

### 단기 로드맵

1. `candles_1d` 실시간 upsert 병합: `claude/sleepy-dhawan-48ddcb` 브랜치의 `CandleAggregator.flush()` 변경(`candles_1d` upsert + `backfillOnStartup()`)을 리뷰 후 병합하고, ADR 번호 충돌(`ADR-020`)을 해소한다.
2. `PriceTickDbWriter` 처리 방향 결정: (a) `TickKafkaConsumer`/`TickPipelineConfig`에 실제로 연결해 `price_ticks`를 채우거나, (b) 당장 필요하지 않다면 dead code로 명시하고 제거한다. 둘 다 하지 않으면 문서와 코드가 다시 어긋난다.
3. hypertable 전환 자동화: `init-timescaledb.sql`을 `docker-compose.yml`(로컬)과 CI 파이프라인에 실제로 연결할지 결정한다. 연결하지 않는 한 3, 5, 7, 8장의 TimescaleDB 전용 기능(chunk pruning, CAgg, 보존 정책)은 계속 죽은 설계로 남는다.
4. (hypertable 전환 이후) Continuous Aggregate 활성화 및 `CandleRepository`에 CAgg/테이블 fallback 로직 추가.

### 장기 방향

- **chunk 압축**: 7일 이상 된 chunk에 `compress_chunk()`를 적용하여 스토리지 절감. 압축된 chunk는 append-only이므로, 과거 데이터 수정이 필요하면 압축 해제 후 재압축 절차가 필요하다.
- **공간 파티셔닝**: 종목 수가 매우 많아지면 `stock_id`를 기준으로 space dimension을 추가하여 chunk를 더 잘게 분할할 수 있다.
- **실시간 분봉**: WebSocket으로 현재 진행 중인 분봉의 임시 OHLCV를 메모리에서 직접 스트리밍하고, 분이 완성된 시점에 DB에 확정 기록하는 방식으로 지연 없는 차트를 구현할 수 있다.
