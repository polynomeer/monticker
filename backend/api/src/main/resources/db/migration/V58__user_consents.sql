-- ADR-068: 약관·개인정보·연령·연동 고지 동의 기록. 추가만 한다(철회도 agreed=false 행) — 현재 상태는 항목별 최신 행.
-- users 삭제는 소프트 삭제(deleted_at)라 FK는 기본(RESTRICT)으로 둔다 — 동의 증빙이 계정과 함께 사라지지 않게.
CREATE TABLE user_consents (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users(id),
    consent_type     VARCHAR(40) NOT NULL,
    document_version VARCHAR(40) NOT NULL,
    agreed           BOOLEAN     NOT NULL,
    source           VARCHAR(30) NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_consents_user ON user_consents (user_id, recorded_at DESC, id DESC);
