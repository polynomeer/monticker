-- users.deleted_at 부분 인덱스 — soft-delete 필터 쿼리 최적화
CREATE INDEX IF NOT EXISTS idx_users_active
    ON users (id) WHERE deleted_at IS NULL;

-- users.email_verified — 미인증 사용자 조회
CREATE INDEX IF NOT EXISTS idx_users_email_verified
    ON users (email_verified) WHERE email_verified = false;

-- alert_rules — 복합 인덱스 (is_active + stock_id) — 틱당 룰 조회
CREATE INDEX IF NOT EXISTS idx_alert_rules_active_stock
    ON alert_rules (stock_id, is_active) WHERE is_active = true;

-- alert_histories — 사용자별 최근 이력 조회
CREATE INDEX IF NOT EXISTS idx_alert_histories_rule_triggered
    ON alert_histories (rule_id, triggered_at DESC);

-- paper_trades — 페이지네이션 쿼리
CREATE INDEX IF NOT EXISTS idx_paper_trades_user_traded
    ON paper_trades (user_id, traded_at DESC);
