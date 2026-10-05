-- 결과 불명 주문의 관리자 수동 확정(ADR-056 Note 2026-10-04). 매칭 후보가 둘 이상이라 대조 잡이 고르지 못한 주문(needs_review)을
-- 운영자가 증권사 주문번호를 지정하거나 미접수로 확정한다. 누가·왜 확정했는지 행에 남긴다.
ALTER TABLE brokerage_orders DROP CONSTRAINT IF EXISTS brokerage_orders_resolved_by_check;
ALTER TABLE brokerage_orders ADD CONSTRAINT brokerage_orders_resolved_by_check
    CHECK (resolved_by IS NULL OR resolved_by IN ('BROKER_LOOKUP','NOT_FOUND','MANUAL'));
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS resolved_by_user BIGINT REFERENCES users(id);
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS resolution_note TEXT;
