-- ADR-077 — watch rule 확장: 규칙 이름, 퀀트랩 전략 신호 트리거, 복합 조건(동반 이벤트), 하루 최대 발동.

ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS name                 VARCHAR(100);
-- event_type = 'QUANT_SIGNAL'이면 이 전략(룰셋, Mongo ObjectId)의 이 방향 신호에 발동한다.
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS rule_set_id          VARCHAR(24);
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS signal_direction     VARCHAR(4);
-- 복합 조건: 주 이벤트가 감지된 시점 앞 condition_window_sec 안에 이 이벤트 유형들도 모두 감지됐어야 한다(쉼표 구분).
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS required_event_types VARCHAR(200);
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS condition_window_sec INTEGER;
-- 하루(KST) 최대 체결 횟수. NULL이면 제한 없음.
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS daily_limit          INTEGER;

ALTER TABLE watch_rules ADD CONSTRAINT ck_watch_rules_quant_signal CHECK (
    event_type <> 'QUANT_SIGNAL' OR (rule_set_id IS NOT NULL AND signal_direction IN ('BUY', 'SELL'))
);
ALTER TABLE watch_rules ADD CONSTRAINT ck_watch_rules_daily_limit CHECK (daily_limit IS NULL OR daily_limit BETWEEN 1 AND 1000);
ALTER TABLE watch_rules ADD CONSTRAINT ck_watch_rules_condition_window CHECK (condition_window_sec IS NULL OR condition_window_sec BETWEEN 60 AND 86400);

-- 퀀트 신호 발동은 stock_event가 없다 — 발동 기록은 둘 중 하나를 가리킨다.
ALTER TABLE watch_rule_executions ALTER COLUMN stock_event_id DROP NOT NULL;
ALTER TABLE watch_rule_executions ADD COLUMN IF NOT EXISTS quant_signal_id BIGINT;
ALTER TABLE watch_rule_executions ADD CONSTRAINT ck_watch_rule_exec_trigger CHECK (
    (stock_event_id IS NOT NULL) <> (quant_signal_id IS NOT NULL)
);
-- (룰, 신호) 멱등 — (룰, 이벤트)의 ux_watch_rule_exec_idempotency와 같은 역할.
CREATE UNIQUE INDEX IF NOT EXISTS ux_watch_rule_exec_signal
    ON watch_rule_executions (watch_rule_id, quant_signal_id) WHERE quant_signal_id IS NOT NULL;

-- 하루 발동 슬롯. "확인 후 주문"이 아니라 조건부 UPSERT 하나로 슬롯을 잡아 동시 발동이 한도를 넘지 못한다.
CREATE TABLE IF NOT EXISTS watch_rule_daily_counts (
    watch_rule_id BIGINT  NOT NULL REFERENCES watch_rules(id) ON DELETE CASCADE,
    day           DATE    NOT NULL,
    executed      INTEGER NOT NULL DEFAULT 0 CHECK (executed >= 0),
    PRIMARY KEY (watch_rule_id, day)
);

CREATE INDEX IF NOT EXISTS idx_watch_rules_quant_signal ON watch_rules (rule_set_id, stock_id) WHERE event_type = 'QUANT_SIGNAL' AND is_active;
