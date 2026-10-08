-- flyway:executeInTransaction=false
-- ADR-100 — 관심종목 뉴스·공시 알림의 사용자별 시간당 상한. worker NewsAlertFanout이 기사·공시마다
-- "이 사용자가 최근 1시간에 받은 NEWS 이력 수"를 센다(user_id IN 관심종목 주인, category = 'NEWS', triggered_at >= now - 1h).
-- 기존 인덱스(uq_alert_histories_user_dedup: user_id, dedup_key)로는 사용자 행 전체를 훑어야 해 이력이 쌓일수록 느려진다.
--
-- 규칙 행(rule_id)은 user_id가 NULL이라 부분 인덱스에서 빠진다 — 규칙 이력은 이 인덱스를 쓰지 않는다.
-- alert_histories는 worker가 계속 쓰는 테이블이라 CONCURRENTLY로 만든다(V84와 같은 규칙: 트랜잭션 밖, 문장 하나).
-- 생성이 중간에 실패하면 INVALID 인덱스가 남고 IF NOT EXISTS가 그것을 건너뛴다 — 재시도 전에
-- `DROP INDEX CONCURRENTLY IF EXISTS idx_alert_histories_user_category_time;` 후 `flyway repair`.
-- 이 인덱스가 없어도(worker를 api보다 먼저 배포) 팬아웃은 같은 결과를 낸다 — 느릴 뿐이다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_alert_histories_user_category_time
    ON alert_histories (user_id, category, triggered_at) WHERE user_id IS NOT NULL;
