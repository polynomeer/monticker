-- ADR-037 — 종목 커뮤니티 댓글. 종목 전체 게시판 + 선택적 이벤트 태그.
CREATE TABLE IF NOT EXISTS stock_comments (
    id          BIGSERIAL       PRIMARY KEY,
    stock_id    BIGINT          NOT NULL REFERENCES stocks(id),
    event_id    BIGINT          REFERENCES stock_events(id),
    user_id     BIGINT          NOT NULL REFERENCES users(id),
    content     TEXT            NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_stock_comments_stock ON stock_comments (stock_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_stock_comments_event ON stock_comments (event_id) WHERE event_id IS NOT NULL;

-- 신고는 저장만 한다 — 자동 숨김/처리 워크플로우는 범위 밖(ADR-037 Consequences).
CREATE TABLE IF NOT EXISTS stock_comment_reports (
    id          BIGSERIAL       PRIMARY KEY,
    comment_id  BIGINT          NOT NULL REFERENCES stock_comments(id),
    reporter_id BIGINT          NOT NULL REFERENCES users(id),
    reason      TEXT            NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    UNIQUE (comment_id, reporter_id)
);
