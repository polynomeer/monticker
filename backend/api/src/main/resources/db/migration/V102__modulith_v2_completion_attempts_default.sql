-- ADR-104 — Spring Modulith 2 JPA 레지스트리의 completionAttempts는 primitive int다. 컬럼이 NULL인 행(1.x가 쓴 행,
-- 롤링 배포 중 구버전 pod가 쓰는 행)을 하나라도 읽으면 Hibernate가 "null을 primitive에 넣을 수 없다"로 실패하고,
-- 그 조회(재전송 대상 고르기)가 통째로 실패한다. 기존 행을 0으로 채우고, 구버전이 이 컬럼 없이 넣는 행도 0이 되게
-- 기본값을 둔다. worker_outbox(JDBC 레지스트리)는 NULL을 읽을 수 있지만 `completion_attempts + 1`이 NULL로 남지
-- 않게 같이 맞춘다. 재전송 대상 표시(status)는 LegacyPublicationStatusBackfill이 주기마다 한다.
UPDATE event_publication SET completion_attempts = 0 WHERE completion_attempts IS NULL;
ALTER TABLE event_publication ALTER COLUMN completion_attempts SET DEFAULT 0;

UPDATE worker_outbox.event_publication SET completion_attempts = 0 WHERE completion_attempts IS NULL;
ALTER TABLE worker_outbox.event_publication ALTER COLUMN completion_attempts SET DEFAULT 0;
