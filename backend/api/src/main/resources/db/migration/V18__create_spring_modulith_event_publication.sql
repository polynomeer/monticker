-- Spring Modulith ApplicationEventPublicationRegistry 저장소
-- @Externalized 이벤트의 발행 상태(PUBLISHED/FAILED)를 추적한다.
CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID        NOT NULL PRIMARY KEY,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_event_publication_completion_date
    ON event_publication (completion_date);
