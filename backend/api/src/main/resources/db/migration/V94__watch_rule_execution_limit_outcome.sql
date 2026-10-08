-- ADR-098 — 지정가 발동(PLACED)의 이후 결과를 발동 기록에 반영한다(ADR-095 "남긴 것").
--   PLACED → FILLED     스위퍼(ADR-074)가 체결했다. fill_price = 체결가, resolved_at = 체결 시각
--   PLACED → CANCELLED  사용자 취소·체결 시점 리스크 차단·보유 부족으로 취소됐다. reason = 취소 사유, resolved_at = 취소 시각
-- 전이는 주문 상태 변경과 같은 트랜잭션(동기 리스너)에서 `WHERE status = 'PLACED'` 조건부 UPDATE로 한 번만 일어난다.
-- 발동 기록과 주문 접수가 서로 다른 트랜잭션이라 생기는 틈은 기록 직후 주문 행을 잠그고 대조(reconcile)해서 메운다.
-- 규칙 경유 손익(ADR-085)은 paper_trades.origin에서 계산하므로 이 전이가 손익에 더해지지 않는다(이중 집계 없음).

ALTER TABLE watch_rule_executions ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;

ALTER TABLE watch_rule_executions DROP CONSTRAINT IF EXISTS ck_watch_rule_exec_status;
ALTER TABLE watch_rule_executions ADD CONSTRAINT ck_watch_rule_exec_status
    CHECK (status IN ('EXECUTED', 'PLACED', 'FILLED', 'CANCELLED', 'REJECTED', 'SKIPPED'));

-- 결과 시각은 PLACED 이후 결과(FILLED·CANCELLED)에만 있다. FILLED에는 체결가가 있다.
ALTER TABLE watch_rule_executions ADD CONSTRAINT ck_watch_rule_exec_resolved CHECK (
    (status IN ('FILLED', 'CANCELLED')) = (resolved_at IS NOT NULL)
    AND (status <> 'FILLED' OR fill_price IS NOT NULL)
);

-- 체결·취소 이벤트마다 "이 주문의 미체결 발동 기록"을 찾는다. 미체결 행만 담는 부분 인덱스라 작다.
-- watch_rule_executions는 규칙 발동마다 한 줄씩 쓰는 낮은 쓰기량 테이블이라 CONCURRENTLY 없이 만든다
-- (위 ALTER와 한 트랜잭션에 둔다 — CONCURRENTLY는 단독 비트랜잭션 스크립트여야 한다, V84 참고).
CREATE INDEX IF NOT EXISTS idx_watch_rule_exec_placed_order
    ON watch_rule_executions (order_id) WHERE status = 'PLACED';
