# ADR-041: TimescaleDB hypertable 승격·압축 정책, 그리고 원시 틱 미저장 확정

## Status
Accepted

## Context

[ADR-002](002-timescaledb.md)는 시계열 데이터를 TimescaleDB로 다루기로 결정하면서
Consequences에 이렇게 적었다:

> Flyway migrations must call `create_hypertable()` after table creation.

**이 결정은 한 번도 이행되지 않았다.** `create_hypertable()` 호출은 Flyway 마이그레이션이
아니라 `infra/docker/init-timescaledb.sql`에 있고, 이 파일은 `docker-compose.yml`·
`Makefile`·`dev.sh`·CI 워크플로 **어디에서도 실행되지 않는다**. 저장소 내 유일한 참조는
문서 3곳뿐이다([ADR-021](021-candles-1d-realtime-upsert.md),
[timescaledb-candle-pipeline.md](../technical/timescaledb-candle-pipeline.md) 2곳)이며,
두 문서 모두 이미 "미가동 상태"라고 명시하고 있다.

따라서 현재 `price_ticks` / `candles_1m` / `candles_1d`는 **평범한 PostgreSQL 테이블**이다.
chunk pruning 없음, 압축 없음, 보존 정책 없음. `V10__create_candle_aggregates.sql`이
만들려는 Continuous Aggregate는 "price_ticks가 하이퍼테이블이면"이라는 조건에 걸려
생성된 적이 없다.

### 이번에 추가로 확인한 것

`price_ticks`를 다시 들여다보다가 더 근본적인 사실을 발견했다.

- [`PriceTickDbWriter`](../../backend/worker/src/main/kotlin/com/monticker/worker/marketdata/PriceTickDbWriter.kt)는
  `@Component`로 등록돼 있지만 **호출부가 0건이다.**
  `TickKafkaConsumer`도, Spring Integration 파이프라인(`TickPipelineConfig`)도 이걸 쓰지
  않는다(`redisTickWriter` / `candleAggregator` / `eventDetector`만 호출한다).
- `price_ticks`를 **읽는** 코드도 0건이다. 캔들은 `CandleAggregator`가 인메모리 집계 후
  `candles_1m`/`candles_1d`에 직접 upsert하므로 `price_ticks`를 경유하지 않는다.
- `LatencyTracker.recordDbWrite()` 역시 호출부가 없다 →
  `GET /api/latency`의 `dbWrite` 단계는 항상 비어 있다.

즉 **`price_ticks`는 지금까지 단 한 행도 쓰인 적이 없고, 읽힌 적도 없다.**
그리고 `candles_1m_cagg` / `candles_1d_cagg`는 `price_ticks`에서 집계하는 뷰이므로,
설령 하이퍼테이블 전환이 됐더라도 **소스 데이터가 없어서 비어 있었을** 것이다 —
조건과 데이터 양쪽으로 이중으로 죽어 있었다.

이 발견은 [scale-out-plan.md](../scale-out-plan.md) §1.1의 용량 산정을 바꾼다.
그 문서는 T2에서 `price_ticks`가 연 17.5TB 증가한다고 적었는데, 그건 "원시 틱을
Postgres에 저장한다면"이라는 가정 위의 투영이었다. 지금은 0이다.
**따라서 "원시 틱을 저장할 것인가"가 먼저 답해야 할 질문이 된다.**

### 후보

- **A) 원시 틱을 저장한다** — `PriceTickDbWriter`를 배선하고 hypertable + 압축 + 90일 보존.
- **B) 원시 틱을 Postgres에 저장하지 않는다** — 죽은 코드/스키마를 제거하고, 캔들만 승격한다.
- **C) 지금은 결정하지 않고 하이퍼테이블 승격만 한다** — 죽은 코드를 그대로 둔다.

## Decision

**B안** — 캔들만 승격하고, 원시 틱은 Postgres에 저장하지 않기로 확정한다.

### 1. `candles_1m` / `candles_1d`를 Flyway로 hypertable 승격

```sql
-- V42__promote_candles_to_hypertables.sql
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
     RAISE NOTICE 'timescaledb 확장 없음 — 일반 테이블 유지';
     RETURN;
  END IF;

  PERFORM create_hypertable('candles_1m', 'candle_time',
      chunk_time_interval => INTERVAL '7 days',
      migrate_data => TRUE, if_not_exists => TRUE);

  PERFORM create_hypertable('candles_1d', 'candle_time',
      chunk_time_interval => INTERVAL '30 days',
      migrate_data => TRUE, if_not_exists => TRUE);
END $$;
```

### 2. 압축 정책 — `compress_after`는 upsert 윈도우보다 뒤여야 한다

**압축된 chunk는 UPDATE할 수 없다.** [ADR-021](021-candles-1d-realtime-upsert.md)이
`CandleAggregator.flush()`에서 `candles_1d`의 **당일 행을 장중 내내 upsert**하도록
결정했으므로, 압축 시점과 upsert 윈도우 사이에 반드시 여유가 있어야 한다.

```sql
ALTER TABLE candles_1m SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'stock_id',      -- 같은 종목 행을 묶어야 압축률이 나온다
  timescaledb.compress_orderby   = 'candle_time DESC'
);
SELECT add_compression_policy('candles_1m', INTERVAL '14 days');

ALTER TABLE candles_1d SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'stock_id',
  timescaledb.compress_orderby   = 'candle_time DESC'
);
SELECT add_compression_policy('candles_1d', INTERVAL '90 days');
```

| 테이블 | upsert가 닿는 범위 | chunk 간격 | `compress_after` | 여유 |
|--------|------------------|-----------|-----------------|------|
| `candles_1m` | 현재 분 (지연 틱 포함 수 분) | 7일 | 14일 | 충분 |
| `candles_1d` | 당일 (KST 달력일 내내) | 30일 | 90일 | 충분 |

이건 **두 ADR 사이의 계약**이다. ADR-021의 실시간 upsert 설계를 바꾸거나
`compress_after`를 줄이면 `flush()`가 조용히 실패한다 —
현재 `CandleAggregator.flush()`의 `catch`는 ERROR 로그만 남기고 넘어간다.
따라서 **flush 실패를 카운터 메트릭으로 노출하고 알람을 건다**(§Consequences).

### 3. 보존 정책 — 캔들은 삭제하지 않는다

`add_retention_policy`를 걸지 않는다. 캔들은 백테스트의 주 데이터이고, 압축 후
용량이 작다(scale-out-plan §1.1 기준 `candles_1m` 연 150GB → 압축 후 10~15GB).
장기 아카이브(Parquet)는 Phase 2에서 다룬다.

### 4. 원시 틱을 Postgres에 저장하지 않는다

- `PriceTickDbWriter`와 `LatencyTracker.recordDbWrite()` / `/api/latency`의 `dbWrite`
  단계를 **삭제**한다.
- `price_ticks` 테이블을 **드롭**한다. 단 비어 있는지 확인한 뒤에만:

```sql
DO $$
DECLARE cnt BIGINT;
BEGIN
  SELECT count(*) INTO cnt FROM price_ticks;
  IF cnt = 0 THEN
     DROP TABLE price_ticks;
  ELSE
     RAISE EXCEPTION 'price_ticks에 %행이 있다 — 수동 확인 필요', cnt;
  END IF;
END $$;
```

- 틱 단위 데이터가 필요하면 **Kafka `market.ticks`(retention 6h,
  [ADR-040](040-kafka-topic-declaration.md))가 유일한 소스**다. 장기 보관은 Phase 2에서
  아카이브 워커 → Parquet → 오브젝트 스토리지로 설계한다.

### 5. Continuous Aggregate를 채택하지 않는다

`candles_1m` / `candles_1d`는 `CandleAggregator`가 직접 쓰는 실체 테이블이다.
`price_ticks`가 사라지면 CAgg의 소스 자체가 없어진다. `V10`은 조건에 걸려 아무것도
만들지 않은 채 이미 적용 완료로 기록돼 있으므로 되돌릴 것이 없다 —
새 마이그레이션에서 CAgg 생성을 시도하지 않는다.

### 6. 공간 파티셔닝(`add_dimension`)을 쓰지 않는다

단일 노드에서는 시간 차원 파티셔닝만으로 충분하고, 공간 차원을 추가하면 chunk 수만
곱해져 플래너 부담이 커진다. 멀티노드로 갈 때 다시 검토한다.

### 7. CI에서 승격 상태를 검증한다

```kotlin
@Test fun `캔들 테이블은 hypertable이어야 한다`() {
    val names = jdbc.queryForList(
        "SELECT hypertable_name FROM timescaledb_information.hypertables", String::class.java)
    assertThat(names).contains("candles_1m", "candles_1d")
}
```

이게 없으면 언젠가 또 조용히 미가동 상태로 돌아간다. ADR-002가 정확히 그렇게 됐다.

## Reasons

- **지금이 승격 비용이 가장 싼 시점이다.** `migrate_data => TRUE`는 기존 행을 chunk로
  옮기며 테이블을 잠근다. 데이터가 작은 지금은 순식간이지만, T2 규모에서 하면 다운타임이
  된다. 미루면 미룰수록 비싸지는 종류의 작업이다.
- **B안(원시 틱 미저장)을 고른 이유**:
  - 아무도 쓰지 않는 경로를 "언젠가 필요할지 모르니" 배선하는 건 CLAUDE.md가 금지하는
    가상의 미래 요구를 위한 설계다. 실제로 틱 단위 데이터가 필요해진 요구는 아직 없다.
  - 필요해지더라도 **목적지가 Postgres가 아니다.** T2에서 연 17.5TB는 OLTP 클러스터에
    둘 데이터가 아니다(scale-out-plan §4 원칙 4: "틱은 저장이 아니라 흐름이다").
    지금 A안으로 배선하면 나중에 아카이브로 옮길 때 두 번 일하게 된다.
  - 죽은 코드를 남겨두면 다음 사람이 "이미 저장되고 있다"고 오해한다. 실제로 이 저장소의
    `docs/data-model.md`와 `/api/latency`의 `dbWrite` 지표가 이미 그런 오해를 유도하고 있다.
- **C안(결정 유보)을 고르지 않은 이유**: 유보는 이미 한 번 해봤다. ADR-002가 남긴
  "Flyway가 `create_hypertable()`을 호출해야 한다"는 문장은 결정이 아니라 유보였고,
  그 결과 4년치 문서가 실제로 존재하지 않는 인프라를 설명하게 됐다.
- **압축 `segmentby`를 `stock_id`로 두는 이유**: TimescaleDB 압축은 segment 안의 값들을
  배열로 묶는다. 종목이 섞인 채 압축하면 가격·거래량 컬럼의 값 분포가 무작위가 돼
  압축률이 나오지 않는다. 조회도 항상 `WHERE stock_id = ?`로 좁혀지므로 세그먼트 pruning
  이점까지 얻는다.

## Consequences

- **`price_ticks`가 사라진다.** `docs/data-model.md`의 해당 섹션과 다이어그램,
  `docs/technical/timescaledb-candle-pipeline.md`의 관련 장을 함께 고쳐야 한다 —
  코드보다 앞서간 문서를 또 남기지 않는다.
- **틱 단위 재생·감사가 Kafka retention(6h) 안에서만 가능하다.** 실브로커 주문
  ([ADR-025](025-real-brokerage-order-safety-gate.md))이나 AI 주문 제안
  ([ADR-036](036-ai-order-proposal.md))에 대해 "그 시점 시세가 얼마였는지"를 6시간 넘게
  지나서 증명해야 하는 규제·분쟁 요구가 생기면 **이 결정을 앞당겨 재검토해야 한다.**
  현재는 `candles_1m`(분봉)이 남으므로 분 단위 근거는 영구 보존된다.
- **압축 chunk와 upsert 사이에 계약이 생긴다**(§Decision 2). 이를 지키기 위해:
  - `CandleAggregator.flush()` 실패를 `candle_flush_failed_total` 카운터로 노출하고
    0보다 크면 알람한다. 지금은 ERROR 로그뿐이라 조용히 캔들이 비어갈 수 있다.
  - `compress_after` 값을 줄이는 변경은 ADR-021을 함께 읽고 판단해야 한다.
- **`candles_1d`의 chunk 간격이 30일이므로 압축 전 chunk 하나가 크다**
  (12,000종목 × 30일 = 36만 행). 문제되는 규모는 아니다.
- **CAgg를 안 쓰므로 ADR-002가 든 근거 중 "continuous aggregates가 캔들 집계를
  단순화한다"는 실현되지 않는다.** 나머지 근거(시간 파티셔닝, PostgreSQL 확장,
  별도 인프라 불필요)는 그대로 유효하므로 ADR-002를 Superseded로 바꾸지 않는다.
- **hypertable 전환은 되돌리기 어렵다.** chunk로 쪼개진 테이블을 일반 테이블로 되돌리려면
  데이터를 복사해야 한다. 실행 전 백업 검증(`infra/db/`의 PITR 스크립트 리허설)을
  사전 조건으로 둔다.
- 로컬 개발 환경은 이미 `timescale/timescaledb:latest-pg16` 이미지를 쓰므로 확장이
  존재한다. 확장이 없는 환경(일부 매니지드 Postgres)에서는 `RAISE NOTICE` 후 일반
  테이블로 남는다 — 기능은 동작하되 압축·pruning 이점만 없다.

## Revisit When

- **틱 단위 데이터의 장기 보관 요구가 실제로 생길 때** — 규제·분쟁 대응, 틱 해상도
  백테스트, 이벤트 감지 재현 중 하나라도 구체적 요구가 되면. 그때는 Postgres가 아니라
  Kafka → Parquet → 오브젝트 스토리지 경로를 만든다(scale-out-plan §6.2.2, Phase 2).
- **`candles_1m` 쓰기가 단일 Timescale 노드의 한계에 닿을 때** — ADR-002의 원래
  Revisit 조건이다. 그 전에 캔들 파이프라인 재설계(Redis 상태 + 배치 INSERT,
  scale-out-plan §6.2.3)를 먼저 한다.
- **ADR-021의 실시간 upsert를 걷어낼 때** — 전일 종가를 Redis로 옮기면 `candles_1d`
  장중 upsert가 필요 없어지고, 그러면 `compress_after`를 훨씬 짧게 잡을 수 있다.
