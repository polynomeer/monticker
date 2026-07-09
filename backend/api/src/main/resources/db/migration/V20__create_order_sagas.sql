-- Order Saga 상태 추적 테이블.
-- 각 주문 처리 흐름을 단계별로 기록해 장애 시 보상 트랜잭션을 재현할 수 있도록 한다.
-- status: STARTED → COMPLETED | COMPENSATING → COMPENSATED | FAILED
CREATE TABLE order_sagas (
    id               UUID          NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          BIGINT        NOT NULL REFERENCES users(id),
    order_id         BIGINT        REFERENCES orders(id),
    stock_id         BIGINT        NOT NULL,
    side             VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity         INTEGER       NOT NULL,
    current_step     VARCHAR(40)   NOT NULL DEFAULT 'INIT',
    status           VARCHAR(20)   NOT NULL DEFAULT 'STARTED'
                                   CHECK (status IN ('STARTED', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED')),
    reserved_amount  NUMERIC(18,4),   -- BUY 시 예약 차감된 금액 (보상에 사용)
    error_message    TEXT,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    compensated_at   TIMESTAMPTZ
);

-- 미완료 사가 탐색용 (RecoverySaga 스케줄러가 사용)
CREATE INDEX idx_order_sagas_incomplete
    ON order_sagas (status, started_at)
    WHERE status IN ('STARTED', 'COMPENSATING');

CREATE INDEX idx_order_sagas_user ON order_sagas (user_id, started_at DESC);
