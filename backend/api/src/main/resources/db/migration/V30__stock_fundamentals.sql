-- 시가총액/PER/PBR/EPS/BPS 일일 스냅샷 (KRX 종목 전용) — ADR-018
-- investor_flow(V29)와 달리 시계열이 아닌 종목당 1행: 스크리너가 ~200개 종목 전체를
-- 필터/정렬하는 쿼리라 LATERAL 서브쿼리 없이 단순 JOIN이 가능하도록 설계.
CREATE TABLE stock_fundamentals (
    stock_id    BIGINT PRIMARY KEY REFERENCES stocks(id) ON DELETE CASCADE,
    market_cap  BIGINT,
    per         NUMERIC(10,2),
    pbr         NUMERIC(10,2),
    eps         NUMERIC(14,2),
    bps         NUMERIC(14,2),
    -- KIS 미설정/응답 없음 등으로 실데이터를 못 받아 의사난수로 채운 행인지 표시 (금융 데이터 정직성)
    is_mocked   BOOLEAN     NOT NULL DEFAULT false,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_fundamentals_market_cap ON stock_fundamentals (market_cap DESC);
