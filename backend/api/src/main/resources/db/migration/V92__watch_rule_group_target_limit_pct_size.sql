-- ADR-095 — watch rule 대상·주문 유형·수량 기준 확장(모의투자 전용, ADR-051).
--   대상: 종목 하나(STOCK, 기존) 또는 내 관심종목 그룹(GROUP) — 그룹 규칙은 평가 시점에 그룹에 든 종목마다 판정한다.
--   주문: 시장가(MARKET, 기존) 또는 발동 시점 가격 대비 오프셋(bps) 지정가(LIMIT).
--   수량: 주 수(SHARES, 기존) 또는 모의 계좌 평가자산의 %(EQUITY_PCT) — 발동 시점에 계산해 정수 주로 내림.

-- ── 대상 ──────────────────────────────────────────────────────────────
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS target_type VARCHAR(10) NOT NULL DEFAULT 'STOCK';
-- FK를 걸지 않는다: 그룹을 지워도 규칙은 남아 "대상 그룹 삭제됨"으로 보여야 한다(아래 트리거가 규칙을 끈다).
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS target_group_id BIGINT;
ALTER TABLE watch_rules ALTER COLUMN stock_id DROP NOT NULL;
ALTER TABLE watch_rules ADD CONSTRAINT ck_watch_rules_target CHECK (
    (target_type = 'STOCK' AND stock_id IS NOT NULL AND target_group_id IS NULL)
    OR (target_type = 'GROUP' AND stock_id IS NULL AND target_group_id IS NOT NULL)
);

-- ── 주문 유형 ─────────────────────────────────────────────────────────
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS order_type VARCHAR(6) NOT NULL DEFAULT 'MARKET';
-- 발동 시점 가격 대비 지정가 오프셋(bps, ±10%). 음수 = 가격 아래. 지정가 규칙에만.
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS limit_offset_bps INTEGER;
ALTER TABLE watch_rules ADD CONSTRAINT ck_watch_rules_order_type CHECK (
    (order_type = 'MARKET' AND limit_offset_bps IS NULL)
    OR (order_type = 'LIMIT' AND limit_offset_bps BETWEEN -1000 AND 1000)
);

-- ── 수량 기준 ─────────────────────────────────────────────────────────
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS size_type VARCHAR(10) NOT NULL DEFAULT 'SHARES';
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS equity_pct NUMERIC(5, 2);
ALTER TABLE watch_rules ALTER COLUMN quantity DROP NOT NULL;
ALTER TABLE watch_rules ADD CONSTRAINT ck_watch_rules_size CHECK (
    (size_type = 'SHARES' AND quantity IS NOT NULL AND equity_pct IS NULL)
    OR (size_type = 'EQUITY_PCT' AND quantity IS NULL AND equity_pct BETWEEN 1 AND 25)
);

-- 그룹 규칙 조회 경로: 이벤트 종목이 든 그룹 → 그 그룹을 대상으로 한 활성 규칙.
CREATE INDEX IF NOT EXISTS idx_watch_rules_group_event
    ON watch_rules (target_group_id, event_type) WHERE target_type = 'GROUP' AND is_active;

-- ── 발동 기록 ─────────────────────────────────────────────────────────
-- 그룹 규칙은 발동마다 종목이 다르다 — 기록에 종목을 남긴다. 기존 행은 규칙의 종목으로 채운다.
ALTER TABLE watch_rule_executions ADD COLUMN IF NOT EXISTS stock_id BIGINT;
UPDATE watch_rule_executions e SET stock_id = r.stock_id
FROM watch_rules r WHERE r.id = e.watch_rule_id AND e.stock_id IS NULL;
-- 지정가 접수(미체결) 발동의 지정가. 체결가(fill_price)와 구분한다.
ALTER TABLE watch_rule_executions ADD COLUMN IF NOT EXISTS limit_price NUMERIC(18, 4);
-- PLACED: 지정가 주문이 접수됐지만 아직 체결되지 않았다(이후 체결은 스위퍼, ADR-074). 발동으로 센다(쿨다운·하루 한도).
ALTER TABLE watch_rule_executions DROP CONSTRAINT IF EXISTS ck_watch_rule_exec_status;
ALTER TABLE watch_rule_executions ADD CONSTRAINT ck_watch_rule_exec_status
    CHECK (status IN ('EXECUTED', 'PLACED', 'REJECTED', 'SKIPPED'));

-- ── 그룹 삭제 → 규칙 중지(조용한 연쇄 삭제 대신) ─────────────────────
-- 어떤 경로로 그룹이 지워지든(화면 삭제·회원 탈퇴 연쇄) 그 그룹을 대상으로 한 규칙을 끈다. 규칙·발동 기록은 남는다.
-- 다시 켜려면 대상이 있어야 하므로 서비스가 거부한다 — 사용자는 새 규칙을 만든다.
CREATE OR REPLACE FUNCTION watch_rules_disable_on_group_delete() RETURNS trigger AS $$
BEGIN
    UPDATE watch_rules SET is_active = false, updated_at = now()
    WHERE target_type = 'GROUP' AND target_group_id = OLD.id AND is_active;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_watchlist_group_delete_disables_rules ON watchlist_groups;
CREATE TRIGGER trg_watchlist_group_delete_disables_rules
    AFTER DELETE ON watchlist_groups
    FOR EACH ROW EXECUTE FUNCTION watch_rules_disable_on_group_delete();
