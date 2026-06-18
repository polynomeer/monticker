CREATE TABLE watchlist_groups (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE watchlist_items (
    id           BIGSERIAL PRIMARY KEY,
    group_id     BIGINT          NOT NULL REFERENCES watchlist_groups(id) ON DELETE CASCADE,
    stock_id     BIGINT          NOT NULL REFERENCES stocks(id),
    memo         TEXT,
    target_price NUMERIC(18, 4),
    sort_order   INTEGER         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_watchlist_items_group_stock UNIQUE (group_id, stock_id)
);

CREATE INDEX idx_watchlist_groups_user_id ON watchlist_groups(user_id);
CREATE INDEX idx_watchlist_items_group_id ON watchlist_items(group_id);
CREATE INDEX idx_watchlist_items_stock_id ON watchlist_items(stock_id);
