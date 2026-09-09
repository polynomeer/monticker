-- ADR-036 — AI 주문 제안(Order Proposal). 모의투자 한정, 사용자 명시적 요청 시에만 생성.
-- LLM은 방향(BUY/SELL/HOLD)과 근거만 생성 — 수량은 사용자가 승인 후 직접 입력, 리스크
-- 게이트가 있는 기존 /api/matching/orders 경로를 그대로 거쳐야만 실제 주문이 된다.
CREATE TABLE IF NOT EXISTS order_proposals (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL REFERENCES users(id),
    stock_id    BIGINT          NOT NULL REFERENCES stocks(id),
    side        VARCHAR(10)     NOT NULL CHECK (side IN ('BUY','SELL','HOLD')),
    reasoning   TEXT            NOT NULL,
    status      VARCHAR(20)     NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ     NOT NULL,
    decided_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_order_proposals_user ON order_proposals (user_id, created_at DESC);
