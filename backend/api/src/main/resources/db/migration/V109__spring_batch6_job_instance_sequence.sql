-- ADR-104 / B4-5 — Spring Batch 6은 JOB_INSTANCE id를 BATCH_JOB_INSTANCE_SEQ에서 뽑는다(5는 BATCH_JOB_SEQ).
-- 공식 마이그레이션(migration/6.0)은 RENAME이지만, 그러면 롤링 배포 중 아직 떠 있는 Batch 5(Boot 3.5) pod가
-- BATCH_JOB_SEQ를 찾지 못해 잡 실행이 실패한다. 둘 다 두고 새 시퀀스를 1,000,000 앞에서 시작해 두 버전이 동시에
-- 인스턴스를 만들어도 id(PK)가 겹치지 않게 한다. 구버전이 모두 내려간 뒤 BATCH_JOB_SEQ를 지운다(ADR-104 Revisit When).
DO $$
DECLARE
    next_id BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relkind = 'S' AND relname = 'batch_job_instance_seq') THEN
        SELECT COALESCE((SELECT MAX(job_instance_id) FROM batch_job_instance), 0) + 1000000 INTO next_id;
        EXECUTE format('CREATE SEQUENCE batch_job_instance_seq START WITH %s MAXVALUE 9223372036854775807 NO CYCLE', next_id);
    END IF;
END $$;
