-- ADR-057 — 실거래 주문 킬 스위치.
--
-- 행은 지우지 않는다. 해제는 lifted_* 를 한 번 채우는 것이다 — 이 테이블이 곧 "누가 언제 왜 켜고 껐는가"의 감사 기록이다.
-- 앱이 죽었을 때의 비상 경로: INSERT INTO trading_halts (scope, reason) VALUES ('GLOBAL', '...');
-- 캐시가 없으므로 다음 주문부터 즉시 막힌다(docs/runbooks/trading-halt.md).
CREATE TABLE IF NOT EXISTS trading_halts (
    id          BIGSERIAL    PRIMARY KEY,
    scope       VARCHAR(10)  NOT NULL CHECK (scope IN ('GLOBAL','PROVIDER','USER')),
    target      VARCHAR(50),                          -- PROVIDER: KIS|TOSS, USER: users.id, GLOBAL: NULL
    reason      TEXT         NOT NULL CHECK (length(trim(reason)) > 0),
    halted_by   BIGINT       REFERENCES users(id),    -- NULL = SQL로 직접 켰다
    halted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lifted_by   BIGINT       REFERENCES users(id),
    lifted_at   TIMESTAMPTZ,
    lift_reason TEXT,
    CHECK ((scope = 'GLOBAL') = (target IS NULL)),
    CHECK (scope <> 'PROVIDER' OR target IN ('KIS','TOSS')),
    -- 판정은 target = users.id::text 로 비교한다. SQL로 직접 '007'을 넣으면 영영 걸리지 않으므로 정규형만 받는다.
    CHECK (scope <> 'USER' OR target ~ '^[1-9][0-9]*$')
);

-- 같은 범위·대상에 활성 스위치는 하나. 주문 경로의 판정 쿼리도 이 인덱스(활성 행만)를 탄다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_trading_halts_active
    ON trading_halts (scope, COALESCE(target, ''))
    WHERE lifted_at IS NULL;
