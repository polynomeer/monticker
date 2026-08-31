-- 개인/외국인/기관 순매수 (투자자 동향, KRX 종목 전용) — ADR-017
CREATE TABLE investor_flow (
    id                      BIGSERIAL PRIMARY KEY,
    stock_id                BIGINT      NOT NULL REFERENCES stocks(id) ON DELETE CASCADE,
    trade_date              DATE        NOT NULL,
    individual_net_qty      BIGINT      NOT NULL DEFAULT 0,
    foreign_net_qty         BIGINT      NOT NULL DEFAULT 0,
    institution_net_qty     BIGINT      NOT NULL DEFAULT 0,
    individual_net_amount   BIGINT      NOT NULL DEFAULT 0,
    foreign_net_amount      BIGINT      NOT NULL DEFAULT 0,
    institution_net_amount  BIGINT      NOT NULL DEFAULT 0,
    -- KIS 미설정/응답 없음 등으로 실데이터를 못 받아 의사난수로 채운 행인지 표시 (금융 데이터 정직성)
    is_mocked               BOOLEAN     NOT NULL DEFAULT false,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_investor_flow_stock_date UNIQUE (stock_id, trade_date)
);

CREATE INDEX idx_investor_flow_stock_date ON investor_flow (stock_id, trade_date DESC);
