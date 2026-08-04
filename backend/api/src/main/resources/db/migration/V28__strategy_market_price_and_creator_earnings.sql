-- strategy_market: 전략별 구독 가격 추가 (0 = 무료)
ALTER TABLE strategy_market ADD COLUMN IF NOT EXISTS price NUMERIC(10,2) NOT NULL DEFAULT 0;

-- creator_earnings.payment_id: 무료 전략 구독 시 결제 없음 → nullable로 변경
ALTER TABLE creator_earnings ALTER COLUMN payment_id DROP NOT NULL;
