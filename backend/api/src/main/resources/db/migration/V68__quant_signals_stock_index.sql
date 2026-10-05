-- 종목 차트 "퀀트 시그널" 레이어 — 종목별 최신 신호 조회(StockSignalQueryService).
-- 기존 인덱스는 (rule_set_id, signal_time)뿐이라 종목 조건은 룰셋별 스캔 후 필터였다.
CREATE INDEX IF NOT EXISTS idx_quant_signals_stock_time ON quant_signals (stock_id, signal_time DESC);
