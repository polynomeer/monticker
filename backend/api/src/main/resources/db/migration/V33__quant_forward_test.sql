CREATE TABLE quant_forward_tests (
    id                   BIGSERIAL PRIMARY KEY,
    rule_set_id          VARCHAR(24)   NOT NULL,
    rule_set_version     INTEGER       NOT NULL,
    stock_id             BIGINT        NOT NULL REFERENCES stocks(id),
    status               VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    initial_capital      NUMERIC(18,2) NOT NULL,
    cash                 NUMERIC(18,2) NOT NULL,
    holding_qty          INTEGER       NOT NULL DEFAULT 0,
    holding_entry_price  NUMERIC(18,4),
    holding_entry_date   DATE,
    last_evaluated_date  DATE,
    started_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    stopped_at           TIMESTAMPTZ
);

-- 룰셋당 동시에 하나의 RUNNING 인스턴스만 허용
CREATE UNIQUE INDEX idx_quant_forward_tests_active ON quant_forward_tests(rule_set_id) WHERE status = 'RUNNING';
CREATE INDEX idx_quant_forward_tests_ruleset ON quant_forward_tests(rule_set_id);

CREATE TABLE quant_forward_test_equity (
    id                BIGSERIAL PRIMARY KEY,
    forward_test_id   BIGINT        NOT NULL REFERENCES quant_forward_tests(id) ON DELETE CASCADE,
    eval_date         DATE          NOT NULL,
    equity            NUMERIC(18,2) NOT NULL,
    drawdown          NUMERIC(9,6)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (forward_test_id, eval_date)
);

ALTER TABLE quant_signals ADD COLUMN forward_test_id BIGINT REFERENCES quant_forward_tests(id);
ALTER TABLE quant_signals ADD COLUMN eval_date DATE;

-- 같은 포워드 테스트 실행 회차에서 같은 날짜엔 신호를 최대 1건만 허용한다(멱등성 보장 —
-- ADR-024 참고: 스케줄러 중복 실행이나 수동 재시도가 있어도 안전하다).
CREATE UNIQUE INDEX idx_quant_signals_dedup ON quant_signals(forward_test_id, eval_date) WHERE forward_test_id IS NOT NULL;
