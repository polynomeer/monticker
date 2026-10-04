-- ADR-061 — 접수된(SUBMITTED) 실거래 주문의 주기 동기화와 체결 하나에 정산 하나.

-- 동기화 잡이 마지막으로 증권사에 상태를 물어본 시각. 오래된 순으로 돌아가며 확인한다(NULL = 아직 안 물어봤다).
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS status_synced_at TIMESTAMPTZ;

-- 체결 하나에 정산 기록은 하나. 체결 반영 경로가 여럿이라(제출 결과·대조·수동 확정·사용자 동기화·판정 직전 갱신·동기화 잡)
-- 행 락을 잊은 경로가 생겨도 두 번째 정산(= 같은 대금의 이중 입출금)을 DB가 거부한다.
-- 이 인덱스 생성이 실패하면 이미 중복 정산이 있다는 뜻이다 — 자동으로 지우지 말고 원장과 함께 조사할 것:
--   SELECT order_id, COUNT(*) FROM brokerage_settlements WHERE order_id IS NOT NULL GROUP BY 1 HAVING COUNT(*) > 1;
CREATE UNIQUE INDEX IF NOT EXISTS ux_brokerage_settlements_order_id
    ON brokerage_settlements (order_id)
    WHERE order_id IS NOT NULL;
