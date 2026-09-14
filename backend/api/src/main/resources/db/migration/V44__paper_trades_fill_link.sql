-- ADR-047 — 매칭 엔진이 유일한 체결 경로. paper_trades는 계좌의 실행 기록이며 fills와 1:1로 링크된다.
ALTER TABLE paper_trades ADD COLUMN fill_id BIGINT UNIQUE REFERENCES fills(id);
COMMENT ON COLUMN paper_trades.fill_id IS '매칭 엔진 체결(fills.id). ADR-047 이전 구 페이퍼 경로의 거래는 NULL.';

-- 이 ADR 이전에 매칭 엔진으로 체결됐지만 계좌 기록이 없던 체결을 백필한다 — 포지션·정산·행동 점수가 이 행을 본다.
-- (그 시점의 원장 행은 paper_trade_id에 fills.id를 담고 있어 백필된 행과 링크되지 않는다 — V43 코멘트 참고.)
INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at, fill_id)
SELECT f.user_id, f.stock_id, f.side, f.quantity, f.fill_price, f.amount, f.filled_at, f.id
FROM fills f
WHERE NOT EXISTS (SELECT 1 FROM paper_trades p WHERE p.fill_id = f.id);
