-- ADR-072 — 스크리너 저장 조건. criteria는 ScreenerCriteria JSON(서버가 검증한 뒤에만 저장)이다.
CREATE TABLE saved_screens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(40)  NOT NULL,
    criteria   JSONB        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_screens_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_saved_screens_user ON saved_screens (user_id, created_at);
