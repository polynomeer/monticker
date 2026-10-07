-- ADR-073 — 알림 규칙 "끄기(일시 중지)"와 "삭제"를 구분한다.
-- 지금까지 DELETE는 is_active=false로만 표시했다. 다시 켜는 API가 생기면 삭제한 규칙까지 되살릴 수 있게 되므로
-- 삭제 시각을 따로 둔다: 삭제 = is_active=false + deleted_at, 끄기 = is_active=false만.
ALTER TABLE alert_rules ADD COLUMN deleted_at TIMESTAMPTZ;

-- 이전에 비활성화된 규칙은 모두 DELETE(또는 V45 정리)로 꺼진 것이라 삭제로 본다 — 다시 켤 수 없다.
UPDATE alert_rules SET deleted_at = updated_at WHERE is_active = false AND deleted_at IS NULL;
