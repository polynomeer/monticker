-- ADR-106 / docs/account-aggregation-plan.md 1단계 — CSV 가져오기 한 번이 한 묶음(batch)이다.
-- 묶음을 지우면 그 묶음으로 들어온 거래도 지워진다(되돌리기). 원본 파일은 저장하지 않고 해시만 남긴다(최소 수집).

CREATE TABLE portfolio_import_batches (
    id              BIGSERIAL    PRIMARY KEY,
    account_id      BIGINT       NOT NULL REFERENCES portfolio_accounts(id) ON DELETE CASCADE,
    format          VARCHAR(30)  NOT NULL,              -- MONTICKER_V1, CUSTOM, (증권사별 형식은 샘플을 받은 뒤)
    file_sha256     CHAR(64)     NOT NULL,
    row_count       INT          NOT NULL,              -- 파일의 데이터 행 수
    imported_count  INT          NOT NULL,              -- 새로 들어간 거래
    duplicate_count INT          NOT NULL,              -- 이미 있던 거래(지문이 같아 건너뜀)
    skipped_count   INT          NOT NULL,              -- 오류라 건너뛴 행(이용자가 건너뛰기를 고른 경우만)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_portfolio_import_batches_account ON portfolio_import_batches (account_id, created_at DESC);

ALTER TABLE portfolio_transactions
    ADD COLUMN import_batch_id BIGINT REFERENCES portfolio_import_batches(id) ON DELETE CASCADE;

CREATE INDEX idx_portfolio_tx_import_batch ON portfolio_transactions (import_batch_id) WHERE import_batch_id IS NOT NULL;
