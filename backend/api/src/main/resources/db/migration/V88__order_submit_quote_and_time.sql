-- ADR-091 — 모의 주문의 접수 시점 최우선 호가와 접수 시각(체결 품질: 슬리피지·엔진 지연).
--
-- quote_bid/quote_ask: 사가가 주문을 받은 순간의 실시간 최우선 매수·매도 호가(KIS 실시간만, 시뮬레이션 호가는 기록하지 않는다).
-- quote_at:           그 호가 스냅샷의 시각, quote_source: 공급자 이름.
-- submitted_at:       사가 진입 시각(검증·현금 예약 전). 엔진 지연 = 첫 체결 filled_at − submitted_at.
--
-- 모두 nullable — 이 마이그레이션 이전 주문과 호가를 구하지 못한 주문은 NULL이고, 집계는 그 행을 빼고 건수를 따로 보고한다.
-- ADD COLUMN(기본값 없음)은 메타데이터만 바꿔 테이블을 다시 쓰지 않는다. 집계는 기존 idx_fills_user(user_id, filled_at)로
-- 체결을 고른 뒤 orders PK로 조인하므로 새 인덱스는 없다.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS quote_bid    NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS quote_ask    NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS quote_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quote_source VARCHAR(20),
    ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ;

COMMENT ON COLUMN orders.quote_bid IS 'ADR-091 접수 시점 최우선 매수호가(실시간 호가만). NULL = 기록 없음(V88 이전·호가 없음).';
COMMENT ON COLUMN orders.quote_ask IS 'ADR-091 접수 시점 최우선 매도호가(실시간 호가만). NULL = 기록 없음.';
COMMENT ON COLUMN orders.submitted_at IS 'ADR-091 사가 진입 시각. 엔진 지연의 시작점. NULL = V88 이전 주문.';
