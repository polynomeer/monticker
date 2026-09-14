-- backlog §9 — stock_id가 NULL인 알림 룰은 워커의 AlertRuleIndex가 색인하지 않아 평생 발동하지 않는다.
-- 존재하면 비활성화하고(사용자에게 "울릴 것"처럼 보이면 안 된다) 컬럼을 NOT NULL로 만든다.
UPDATE alert_rules SET is_active = false, updated_at = now() WHERE stock_id IS NULL AND is_active = true;
ALTER TABLE alert_rules ALTER COLUMN stock_id SET NOT NULL;
