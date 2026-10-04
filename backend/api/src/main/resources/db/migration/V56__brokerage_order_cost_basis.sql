-- ADR-062 — 매도 주문의 원가(증권사 평단가). 의도를 기록할 때(ADR-056 tx1) 리스크 게이트용으로 받아 온 증권사 잔고에서 그 종목의
-- 평단가를 남긴다. 매도 후에는 보유가 줄거나 사라져 증권사에서 다시 얻을 수 없다. 실거래 일간 실현손익 = Σ 체결수량 × (체결가 − 원가).
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS cost_basis_price NUMERIC(18,4);
