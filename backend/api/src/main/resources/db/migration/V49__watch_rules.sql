-- ADR-051 — 이벤트 트리거 모의 자동주문(watch rule).
-- "이 종목에 이런 이벤트가 감지되면 모의투자 계좌로 N주 매수/매도한다"를 사용자가 미리 선언한다.
-- 모의투자 계좌 전용이다 — 실브로커 계좌 자동 실행은 ADR-025/036의 원칙(사람이 직접 제출)을 깨므로 범위 밖.

CREATE TABLE watch_rules (
    id                   BIGSERIAL    PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users(id),
    stock_id             BIGINT       NOT NULL REFERENCES stocks(id),
    -- worker DetectedEventType 과 같은 값: PRICE_SPIKE / PRICE_DROP / VOLUME_SURGE.
    -- 탐지기가 쓰는 값이라 enum 대신 VARCHAR — 탐지 종류가 늘어도 마이그레이션이 필요 없다.
    event_type           VARCHAR(50)  NOT NULL,
    side                 VARCHAR(4)   NOT NULL,
    quantity             INTEGER      NOT NULL,
    -- 이벤트의 importance_score 가 이 값 미만이면 발동하지 않는다. 0이면 모든 강도에 발동.
    min_importance_score INTEGER      NOT NULL DEFAULT 0,
    -- 같은 룰이 연달아 발동하는 것을 막는 쿨다운. 이벤트 자체도 분 단위로 dedup 되지만(stock_events
    -- 유니크 인덱스), 서로 다른 분에 연속으로 터지면 룰은 매번 발동할 수 있다.
    cooldown_sec         INTEGER      NOT NULL DEFAULT 600,
    is_active            BOOLEAN      NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_watch_rules_side       CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_watch_rules_quantity   CHECK (quantity > 0),
    CONSTRAINT ck_watch_rules_importance CHECK (min_importance_score BETWEEN 0 AND 100),
    CONSTRAINT ck_watch_rules_cooldown   CHECK (cooldown_sec >= 0)
);

-- 컨슈머의 룰 조회 경로: (stock_id, event_type) 으로 활성 룰을 찾는다.
CREATE INDEX idx_watch_rules_stock_event ON watch_rules (stock_id, event_type) WHERE is_active;
CREATE INDEX idx_watch_rules_user        ON watch_rules (user_id);

-- 룰 발동 기록. 성공/거부/건너뜀을 모두 남긴다 — 사용자가 "왜 안 샀지"를 확인할 수 있어야 한다.
CREATE TABLE watch_rule_executions (
    id             BIGSERIAL    PRIMARY KEY,
    watch_rule_id  BIGINT       NOT NULL REFERENCES watch_rules(id) ON DELETE CASCADE,
    user_id        BIGINT       NOT NULL REFERENCES users(id),
    stock_event_id BIGINT       NOT NULL,
    -- EXECUTED: 주문 체결 / REJECTED: 리스크 게이트·잔고 등으로 거부 / SKIPPED: 쿨다운·강도 미달
    status         VARCHAR(20)  NOT NULL,
    order_id       BIGINT,
    fill_price     NUMERIC(18,4),
    quantity       INTEGER,
    reason         TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_watch_rule_exec_status CHECK (status IN ('EXECUTED', 'REJECTED', 'SKIPPED'))
);

-- 멱등 키. 아웃박스는 at-least-once 라 같은 stock_event 가 재전달될 수 있고(ADR-008), Kafka 컨슈머
-- 리밸런싱에서도 중복 소비가 일어난다. (룰, 이벤트) 쌍으로 유니크를 걸어 두 번째 실행 시도를
-- INSERT 단계에서 DB가 거부하게 한다 — 애플리케이션 레벨 "조회 후 판단"은 동시 소비에서 새어나간다.
CREATE UNIQUE INDEX ux_watch_rule_exec_idempotency
    ON watch_rule_executions (watch_rule_id, stock_event_id);

CREATE INDEX idx_watch_rule_exec_user_time ON watch_rule_executions (user_id, created_at DESC);
