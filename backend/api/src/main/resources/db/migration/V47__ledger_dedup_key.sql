-- D-L05-01 후속: paper_trade_id 로 키를 잡을 수 없는 아웃박스 구동 원장(정산완료·주문취소·계좌초기화)의 멱등 키.
-- @ApplicationModuleListener at-least-once 재전달 시 이중 기록을 막는다(FILL/SETTLEMENT 는 V46 의 paper_trade_id 인덱스가 담당).
ALTER TABLE ledger_events ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(80);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_events_dedup_key
    ON ledger_events (dedup_key)
    WHERE dedup_key IS NOT NULL;
