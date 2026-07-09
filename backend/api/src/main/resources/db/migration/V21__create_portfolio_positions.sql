-- CQRS 읽기모델: 사용자별 보유 종목 포지션.
-- paper_trades 집계를 매번 재계산하는 대신 이 테이블을 읽어 O(1) 조회를 제공한다.
-- PortfolioPositionProjection이 PaperTrade 이벤트 수신 시 업데이트한다.
CREATE TABLE portfolio_positions (
    user_id       BIGINT         NOT NULL REFERENCES users(id),
    stock_id      BIGINT         NOT NULL REFERENCES stocks(id),
    net_qty       INTEGER        NOT NULL DEFAULT 0 CHECK (net_qty >= 0),
    avg_buy_price NUMERIC(18,4)  NOT NULL DEFAULT 0,
    total_cost    NUMERIC(18,4)  NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, stock_id)
);

CREATE INDEX idx_portfolio_positions_user ON portfolio_positions (user_id);
