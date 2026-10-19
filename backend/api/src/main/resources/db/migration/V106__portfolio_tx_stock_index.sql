-- ADR-108 — 종목 소식(감지 이벤트·뉴스·공시)이 날 때마다 worker가 "이 종목을 보유한 사용자"를 찾는다(StockAudience).
-- 종목으로 거래를 찾는 인덱스. 매매 행만 본다 — 배당·현금 행은 보유를 바꾸지 않는다.
CREATE INDEX idx_portfolio_tx_stock_trade ON portfolio_transactions (stock_id, account_id, trade_date, id)
    WHERE stock_id IS NOT NULL AND type IN ('BUY', 'SELL');
