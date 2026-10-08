-- ADR-094 — api·worker의 Outbox(Spring Modulith 발행 기록)를 분리한다.
--
-- 두 앱이 public.event_publication 한 테이블을 공유하면서, 각자의 재전송(resubmitIncompletePublicationsOlderThan)이
-- 상대 앱의 미완료 행(상대 앱에만 있는 이벤트 클래스)을 만나 "Unable to locate named class"로 통째로 실패했다 —
-- 5분마다, 영원히. api의 UserNotificationCommand 미완료 행이 재전송되지 않아 알림이 사라진 것이 실측됐다.
--
-- 이후: api = public.event_publication(V18, 그대로), worker = worker_outbox.event_publication
-- (Modulith JDBC 레지스트리 + spring.modulith.events.jdbc.schema=worker_outbox).
--
-- 배포 순서: api(이 마이그레이션) → worker. 이 마이그레이션은 public.event_publication의 구조를 바꾸지 않으므로
-- 아직 떠 있는 구버전 api·worker는 영향을 받지 않는다. 구버전 worker가 이 뒤에도 public에 쓰는 행은 새 worker가
-- 재전송 주기마다 같은 규칙으로 옮겨 간다(worker outbox.LegacyOutboxDrain). 새 worker는 이 테이블이 없으면 기동하지 않는다.

CREATE SCHEMA IF NOT EXISTS worker_outbox;

-- Modulith 1.4.13 spring-modulith-events-jdbc의 schema-postgresql.sql과 같은 구조
CREATE TABLE IF NOT EXISTS worker_outbox.event_publication (
    id               UUID                     NOT NULL PRIMARY KEY,
    listener_id      TEXT                     NOT NULL,
    event_type       TEXT                     NOT NULL,
    serialized_event TEXT                     NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE
);

-- markCompleted(listener_id + serialized_event) 조회용 — Modulith 기본 스키마와 같다
CREATE INDEX IF NOT EXISTS worker_event_publication_serialized_event_hash_idx
    ON worker_outbox.event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS worker_event_publication_by_completion_date_idx
    ON worker_outbox.event_publication (completion_date);

-- worker 소유 미완료 행 이관. 판별 기준은 event_type의 패키지다 — 재전송 실패의 원인이 바로 "이 클래스를 해석할 수
-- 있는 앱"이기 때문이다. 1분 유예는 재전송 기준(OutboxResubmissionConfig)과 같다: 막 기록돼 구버전 worker가 지금
-- 외부화·완료 표시하려는 행을 옮기면 완료 표시가 빈 곳을 갱신하고 옮긴 행이 다시 재전송된다(중복). 1분 미만의 행은
-- 새 worker의 LegacyOutboxDrain이 같은 규칙으로 옮긴다. DELETE … RETURNING → INSERT 한 문장이라 이관은 원자적이다.
-- 완료된 worker 행은 옮기지 않는다 — api의 완료 행 정리(OutboxCompletedCleanup, 보존 7일)가 종류와 무관하게 지운다.
WITH moved AS (
    DELETE FROM event_publication
     WHERE completion_date IS NULL
       AND event_type LIKE 'com.monticker.worker.%'
       AND publication_date < now() - INTERVAL '1 minute'
    RETURNING id, listener_id, event_type, serialized_event, publication_date, completion_date
)
INSERT INTO worker_outbox.event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
SELECT id, listener_id, event_type, serialized_event, publication_date, completion_date
  FROM moved
ON CONFLICT (id) DO NOTHING;
