-- ADR-070 — 리스크 한도 근접 경고(80%)의 하루 한 번 보장.
-- 주기 평가가 같은 사용자·규칙을 하루에도 수백 번 보지만, 알림은 KST 날짜마다 한 번이다. 이 행의 INSERT가 성공한
-- 트랜잭션만 UserNotificationCommand를 발행한다(ON CONFLICT DO NOTHING) — api 인스턴스가 여러 대여도 한 번이다.
CREATE TABLE risk_limit_warnings (
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rule       VARCHAR(40)  NOT NULL,
    kst_date   DATE         NOT NULL,
    usage_pct  NUMERIC(8,2) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, rule, kst_date)
);

-- 지난 날짜 정리(RiskLimitNearWarningJob.purge)용
CREATE INDEX idx_risk_limit_warnings_date ON risk_limit_warnings (kst_date);
