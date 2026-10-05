-- ADR-073 — 알림 읽음 상태. 이력 검색은 ES(alert_histories 인덱스)지만 읽음 상태는 사용자가 바꾸는 값이라
-- DB 원본에만 두고, 검색 결과에 id로 붙인다(ES 문서를 읽음 때마다 다시 색인하지 않는다).
ALTER TABLE alert_histories ADD COLUMN read_at TIMESTAMPTZ;

-- 이 기능 전 이력은 사용자가 이미 본 것으로 둔다 — 그러지 않으면 배포 직후 모든 사용자에게
-- 과거 알림 전부가 "읽지 않음"으로 쌓여 보인다.
UPDATE alert_histories SET read_at = triggered_at WHERE read_at IS NULL;

-- 읽지 않음 개수·모두 읽음은 읽지 않은 행만 본다
CREATE INDEX idx_alert_histories_unread ON alert_histories (rule_id) WHERE read_at IS NULL;
