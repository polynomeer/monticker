-- 소셜 로그인 provider 식별자 저장 (카카오/네이버/구글 고유 ID)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS provider_id VARCHAR(100);

-- 동일 provider + provider_id 중복 가입 방지
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_provider_provider_id
    ON users (provider, provider_id)
    WHERE provider_id IS NOT NULL;
