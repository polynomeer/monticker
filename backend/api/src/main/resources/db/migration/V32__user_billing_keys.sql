-- 정기결제(자동 갱신)용 빌링키 저장. 한 사용자당 하나의 활성 빌링키만 허용한다(카드 교체는
-- upsert로 처리 — BillingController.register 참고).
CREATE TABLE user_billing_keys (
    id            BIGSERIAL     PRIMARY KEY,
    user_id       BIGINT        NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    provider      VARCHAR(20)   NOT NULL DEFAULT 'MOCK',
    customer_key  VARCHAR(50)   NOT NULL,
    -- AES-256-GCM 암호화 저장(EncryptedStringConverter) — 이 값 자체로 결제를 실행할 수
    -- 있으므로 BrokerageAccount.access_token과 동일한 민감도로 취급한다.
    billing_key   TEXT          NOT NULL,
    card_company  VARCHAR(50),
    card_last4    VARCHAR(4),
    issued_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
