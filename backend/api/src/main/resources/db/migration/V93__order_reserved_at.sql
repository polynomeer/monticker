-- ADR-096 — 모의 주문의 "예약 잠금" 시각(지갑 영수증의 접수 → 예약금 잠금 → 체결 → 정산).
--
-- reserved_at: 사가가 주문을 접수하며 잠근 순간.
--   BUY  — 현금 예약(`UPDATE paper_accounts SET cash = cash - ? … AND cash >= ?`)이 성공한 직후.
--   SELL — 포지션 행을 FOR UPDATE로 잡고 매도 가능 수량(보유 − 미체결 매도) 판정을 통과한 직후.
-- 같은 사가 트랜잭션 안에서 주문 INSERT와 함께 기록되므로, 값이 있으면 잠금도 있었다(롤백되면 둘 다 없다).
-- 잠근 금액은 따로 저장하지 않는다 — 지정가 BUY는 정의상 limit_price × quantity(ADR-074)라 행에서 계산된다.
--
-- nullable — 이 마이그레이션 이전 주문은 NULL이고 화면은 "시각 기록 없음"으로 표시한다(잠금 자체는 ADR-074 이후 항상 있었다).
-- ADD COLUMN(기본값 없음)은 메타데이터만 바꿔 테이블을 다시 쓰지 않는다. 조회는 paper_trades → fills → orders PK 조인이라 인덱스는 없다.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS reserved_at TIMESTAMPTZ;

COMMENT ON COLUMN orders.reserved_at IS 'ADR-096 사가가 현금(BUY) 또는 매도 수량(SELL)을 잠근 시각. NULL = V93 이전 주문.';
