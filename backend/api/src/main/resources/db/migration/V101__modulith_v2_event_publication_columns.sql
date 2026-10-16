-- docs/dependency-upgrade-plan.md B4-2 — Spring Modulith 2.x(Spring Boot 4)의 event publication 스키마 v2 컬럼을 미리 넣는다.
-- Modulith 2의 JPA 매핑(api, ddl-auto: validate)은 이 컬럼이 없으면 기동을 거부한다. 지금(Modulith 1.4)은 이 컬럼을 모르고
-- INSERT·SELECT가 컬럼을 이름으로 지정하므로, nullable 컬럼 추가는 무해하다 — 업그레이드 PR과 배포를 떼어 놓는다.
--
-- 값 채우기(backfill)는 하지 않는다: Modulith 2.1.1의 미완료·실패 재전송 쿼리가
-- `status = FAILED or (status is null and completionDate is null)`로 이 마이그레이션 전에 쓰인 행(status NULL)도 집는다
-- (JpaEventPublicationRepository, 2.1.1 소스 확인).
--
-- 컬럼 정의는 Modulith 2.1.1 jar의 schemas/v2/schema-postgresql.sql과 같다. NOT NULL·DEFAULT가 없어 PostgreSQL에서는
-- 테이블을 다시 쓰지 않는 메타데이터 변경이다.

ALTER TABLE event_publication
    ADD COLUMN IF NOT EXISTS status                 TEXT,
    ADD COLUMN IF NOT EXISTS completion_attempts    INT,
    ADD COLUMN IF NOT EXISTS last_resubmission_date TIMESTAMP WITH TIME ZONE;

-- worker의 아웃박스(V91, events-jdbc). worker는 use-legacy-structure로 버틸 수도 있지만 두 테이블을 같은 모양으로 둔다.
ALTER TABLE worker_outbox.event_publication
    ADD COLUMN IF NOT EXISTS status                 TEXT,
    ADD COLUMN IF NOT EXISTS completion_attempts    INT,
    ADD COLUMN IF NOT EXISTS last_resubmission_date TIMESTAMP WITH TIME ZONE;
