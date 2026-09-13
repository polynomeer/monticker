-- ADR-043 — 원장 커서 페이징 + 대사(reconciliation) 스냅샷

-- 1) 커서 페이징 정렬 키는 created_at이 아니라 id (같은 트랜잭션의 이벤트는 created_at이 같을 수 있다).
CREATE INDEX idx_ledger_events_user_id_desc ON ledger_events (user_id, id DESC);

-- 2) 영수증 조회가 findAll()로 전 유저 원장을 읽던 경로 — paper_trade_id로 바로 찾는다.
CREATE INDEX idx_ledger_events_paper_trade ON ledger_events (paper_trade_id) WHERE paper_trade_id IS NOT NULL;

-- 3) paper_trade_id → paper_trades(id) FK 해제.
--    OrderFilledEventListener는 이 컬럼에 fills.id를 넣고(매칭 엔진 경로), paper 경로는 paper_trades.id를 넣는다.
--    FK가 있으면 매칭 엔진 체결의 원장 기록이 FK 위반으로 실패한다(Outbox에 미완료로 남는다).
--    또 원장은 append-only 감사 기록이라(ADR-013) 원 거래 행이 지워져도(PaperTradingService.reset) 살아남아야 한다.
ALTER TABLE ledger_events DROP CONSTRAINT IF EXISTS ledger_events_paper_trade_id_fkey;
COMMENT ON COLUMN ledger_events.paper_trade_id IS
    '원 거래 식별자 — paper 경로는 paper_trades.id, 매칭 엔진 경로는 fills.id. FK 없음 (ADR-043).';

-- 4) 일 단위 대사 스냅샷. 목적은 replay 가속이 아니라 "컬럼 잔고 vs 원장"의 드리프트 감지다.
--    불변식: account_cash + reserved_cash = 10,000,000(초기 지급) + ledger_sum
--    ledger_sum은 현금 컬럼에 실제로 반영되는 이벤트 타입만의 누적 합 (LedgerReconciliationService.CASH_EVENT_TYPES).
CREATE TABLE ledger_snapshots (
    user_id        BIGINT        NOT NULL REFERENCES users(id),
    as_of_date     DATE          NOT NULL,
    ledger_sum     NUMERIC(18,4) NOT NULL,             -- last_event_id까지 현금 영향 이벤트 amount 누적 합
    account_cash   NUMERIC(18,4) NOT NULL,             -- 같은 시점 paper_accounts.cash
    reserved_cash  NUMERIC(18,4) NOT NULL DEFAULT 0,   -- 같은 시점 미체결 BUY 주문 예약금 (limit_price × 잔량)
    last_event_id  BIGINT        NOT NULL,             -- 이 스냅샷에 포함된 마지막 ledger_events.id (다음 대사는 여기서부터 델타)
    mismatch       BOOLEAN       NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, as_of_date)
);
CREATE INDEX idx_ledger_snapshots_mismatch ON ledger_snapshots (as_of_date DESC) WHERE mismatch;
