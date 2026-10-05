# ADR-076: 3분·15분·1시간 봉은 분봉을 조회 시점에 묶는다

## Status
Accepted

## Context

종목 차트의 3분·15분·1시간 버튼이 비활성이었다. 저장된 캔들은 `candles_1m`·`candles_1d` 둘뿐이다
([ADR-041](041-timescale-hypertable-promotion.md) — 둘 다 hypertable, 원시 틱은 저장하지 않는다). 로드맵은
"`candles_3m/15m/1h` 집계"라고 적었다.

### 후보

- **A) TimescaleDB Continuous Aggregate** (`candles_1m` 위 CAgg 3개 + refresh policy)
  - 장점: 조회가 미리 집계된 행을 읽는다.
  - 단점: worker는 분이 끝난 뒤에야 `candles_1m`을 upsert하고(CandleAggregator), CAgg는 refresh 주기만큼 더 늦다.
    real-time aggregation을 켜면 그 차이는 줄지만 CAgg 3개의 정책·압축·백필을 운영해야 한다. 마이그레이션이 TimescaleDB
    확장에 묶인다(V10의 조건부 CAgg처럼 "있으면 만든다"는 이미 한 번 영원히 안 만들어진 전례가 있다 — ADR-041).
- **B) worker가 3m/15m/1h 테이블에 함께 upsert** — 테이블 3개와 쓰기 경로가 늘고, 분 경계 flush(M-002 꼬리 지연의
  원인)가 더 무거워진다.
- **C) 조회 시점 집계** — `candles_1m`에서 `date_bin`으로 묶는다(PostgreSQL 14+ 내장, 확장 불필요).

## Decision

**C.** `GET /api/stocks/{id}/candles?interval=3m|15m|1h` → `CandleRepository.findBucketed`.

```sql
SELECT … FROM (
  SELECT stock_id, date_bin('15 minutes', candle_time, TIMESTAMPTZ '2000-01-01 00:00+00') AS bucket,
         (array_agg(open ORDER BY candle_time))[1] AS open, max(high), min(low),
         (array_agg(close ORDER BY candle_time DESC))[1] AS close, sum(volume)
  FROM candles_1m WHERE stock_id = ? AND candle_time BETWEEN ? AND ?
  GROUP BY stock_id, bucket ORDER BY bucket DESC LIMIT 300
) b ORDER BY candle_time
```

- 기본 조회 구간: 3분 5일, 15분 15일, 1시간 45일(버킷 300개를 넉넉히 덮는 길이). 버킷은 허용 목록(3·15·60)만.
- 같이 고친 것: `findCandles`가 `ORDER BY ASC LIMIT 300`이라 구간이 길면(1분봉 30일, 일봉 1년) **가장 오래된** 300개를
  돌려주던 것을 "가장 최근 300개, 오름차순"으로 바꿨다.

## Reasons

- 한 종목·최대 45일 분봉은 수만 행 이하이고 `(stock_id, candle_time)` 인덱스(hypertable chunk pruning 포함)로 읽는다.
  차트 조회 빈도(10초 폴링)에서 충분하다.
- 쓰기 경로·스키마·운영 정책이 하나도 늘지 않는다. 최신성은 1분봉과 같다(추가 지연 없음).
- `date_bin`의 기준점이 UTC 정시라 KST(+9h) 정시·15분 경계와 그대로 맞는다.

## Consequences

- 압축된 chunk(14일 이전)를 읽는 1시간 봉 조회는 압축 해제 비용이 든다. 45일 범위에서 체감되면 재검토한다.
- 진행 중인 분은 `candles_1m`에 아직 없으므로 마지막 버킷도 직전 완결 분까지만 반영한다(1분봉과 같은 한계).

## Revisit When

- 차트 조회가 DB 부하의 상위에 오를 때 — A(CAgg, real-time aggregation)로 옮긴다. 조회 인터페이스는 그대로 둘 수 있다.
- 더 긴 범위(1시간 봉 1년 등)를 보여 줘야 할 때.
