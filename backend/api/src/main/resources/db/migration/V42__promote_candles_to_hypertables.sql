-- ADR-041 — candles_1m / candles_1d를 TimescaleDB hypertable로 승격하고 압축 정책을 건다.
--
-- ADR-002가 "Flyway가 create_hypertable()을 호출해야 한다"고 적은 뒤로 한 번도 이행되지 않았다.
-- 전환 스크립트(infra/docker/init-timescaledb.sql)는 어떤 실행 경로에도 연결돼 있지 않아
-- 세 테이블은 계속 일반 PostgreSQL 테이블이었다. 지금(데이터가 작을 때)이 승격 비용이 가장 싸다 —
-- migrate_data => TRUE는 기존 행을 chunk로 옮기며 테이블을 잠근다.
--
-- price_ticks는 승격하지 않고 드롭한다: 한 행도 쓰인 적이 없다(PriceTickDbWriter 호출부 0건, 읽는 곳 0건).
-- 원시 틱을 Postgres에 저장하지 않기로 확정했다(ADR-041 §Decision 4). 비어 있을 때만 드롭한다.
--
-- 확장이 없는 환경(일부 매니지드 Postgres, 일반 postgres 이미지)에서는 NOTICE만 남기고 일반 테이블로 둔다 —
-- 기능은 동작하되 chunk pruning·압축 이점만 없다.

DO $$
DECLARE
  tick_rows BIGINT;
BEGIN
  -- ── price_ticks 제거 (비어 있을 때만) ────────────────────────────────────
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'price_ticks') THEN
    SELECT count(*) INTO tick_rows FROM price_ticks;
    IF tick_rows = 0 THEN
      DROP TABLE price_ticks;
      RAISE NOTICE 'price_ticks 드롭 (비어 있음, 한 번도 쓰인 적 없는 테이블 — ADR-041)';
    ELSE
      RAISE EXCEPTION 'price_ticks에 %행이 있다 — 설계상 비어 있어야 한다. 수동 확인 후 진행할 것 (ADR-041)', tick_rows;
    END IF;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
    RAISE NOTICE 'timescaledb 확장 없음 — candles_1m/candles_1d를 일반 테이블로 유지한다 (ADR-041)';
    RETURN;
  END IF;

  -- ── hypertable 승격 ─────────────────────────────────────────────────────
  -- chunk 간격: 1분봉은 7일(T2 기준 chunk당 ~33M행), 일봉은 30일. 공간 파티셔닝(add_dimension)은
  -- 단일 노드에서 chunk 수만 곱하므로 쓰지 않는다.
  PERFORM create_hypertable('candles_1m', 'candle_time',
      chunk_time_interval => INTERVAL '7 days',
      migrate_data => TRUE, if_not_exists => TRUE);
  PERFORM create_hypertable('candles_1d', 'candle_time',
      chunk_time_interval => INTERVAL '30 days',
      migrate_data => TRUE, if_not_exists => TRUE);

  -- ── 압축 ───────────────────────────────────────────────────────────────
  -- segmentby=stock_id: 같은 종목 행을 묶어야 압축률이 나오고 WHERE stock_id=? 조회가 세그먼트 pruning을 받는다.
  -- compress_after는 반드시 "upsert가 더 이상 오지 않는 시간"보다 뒤여야 한다 — 압축된 chunk는 UPDATE할 수 없다.
  --   candles_1m: CandleAggregator가 현재 분(+지연 틱 수 분)을 upsert → 14일이면 충분
  --   candles_1d: ADR-021이 당일 행을 장중 내내 upsert → 90일. 이 값을 줄일 때는 ADR-021과 함께 검토할 것.
  -- 이 계약이 깨지면 CandleAggregator.flush()가 실패한다 — candle_flush_failed_total 알람(P1-2)이 잡는다.
  IF NOT EXISTS (SELECT 1 FROM timescaledb_information.compression_settings WHERE hypertable_name = 'candles_1m') THEN
    ALTER TABLE candles_1m SET (
      timescaledb.compress,
      timescaledb.compress_segmentby = 'stock_id',
      timescaledb.compress_orderby   = 'candle_time DESC'
    );
  END IF;
  PERFORM add_compression_policy('candles_1m', INTERVAL '14 days', if_not_exists => TRUE);

  IF NOT EXISTS (SELECT 1 FROM timescaledb_information.compression_settings WHERE hypertable_name = 'candles_1d') THEN
    ALTER TABLE candles_1d SET (
      timescaledb.compress,
      timescaledb.compress_segmentby = 'stock_id',
      timescaledb.compress_orderby   = 'candle_time DESC'
    );
  END IF;
  PERFORM add_compression_policy('candles_1d', INTERVAL '90 days', if_not_exists => TRUE);

  -- 보존 정책은 걸지 않는다 — 캔들은 백테스트의 주 데이터이고 압축 후 용량이 작다.
  -- Continuous Aggregate(V10)는 채택하지 않는다 — 소스였던 price_ticks가 사라졌고 candles_*는 실체 테이블이다.
  RAISE NOTICE 'candles_1m/candles_1d hypertable 승격 + 압축 정책 완료 (ADR-041)';
END $$;
