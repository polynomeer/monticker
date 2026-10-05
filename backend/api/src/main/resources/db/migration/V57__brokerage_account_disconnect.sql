-- ADR-067: 증권사 연동 해지 — 해지 시각을 남기고, 자격증명(access_token/app_key/app_secret)은 애플리케이션이 NULL로 지운다.
ALTER TABLE brokerage_accounts ADD COLUMN disconnected_at TIMESTAMPTZ;
