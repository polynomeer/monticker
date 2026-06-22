CREATE TABLE device_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      VARCHAR(512) NOT NULL,
    platform   VARCHAR(20)  NOT NULL DEFAULT 'ios',
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_device_tokens_user_token ON device_tokens (user_id, token);
CREATE INDEX idx_device_tokens_user_id ON device_tokens (user_id);
