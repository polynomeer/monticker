-- ADR-106 / docs/account-aggregation-plan.md 0단계 — 여러 출처의 계좌·거래를 담는 통합 포트폴리오 원장.
--
-- 모의계좌는 여기에 옮기지 않는다(paper_trades·portfolio_positions가 그대로 정답, 읽기 어댑터로 붙인다).
-- 주문용 증권사 연동(brokerage_accounts)과도 분리한다 — 실계좌 리스크 검사·조건부 주문은 계속 실시간 잔고를 쓰고
-- (ADR-055), 이 테이블은 표시·알림·복기용이다.
--
-- 보유는 거래(원장)로 계산한다. 증권사·마이데이터가 주는 잔고 스냅샷과 대사하는 테이블은 2단계에서 더한다.
-- 컬럼 모양은 마이데이터 금융투자 표준 API(금투-001 계좌, 금투-003 거래내역)에 맞춘다 — 3단계에서 그대로 채운다.

CREATE TABLE portfolio_accounts (
    id                 BIGSERIAL    PRIMARY KEY,
    user_id            BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 데이터가 들어오는 길. MANUAL(손 입력)·CSV(파일 업로드)가 1단계, BROKER_API·MYDATA는 2·3단계(자문 뒤)
    source             VARCHAR(20)  NOT NULL CHECK (source IN ('MANUAL', 'CSV', 'BROKER_API', 'MYDATA')),
    institution        VARCHAR(30)  NOT NULL,   -- KIS, TOSS, KIWOOM, MIRAE, SAMSUNG, ... , OTHER
    name               VARCHAR(50)  NOT NULL,
    account_kind       VARCHAR(20)  NOT NULL DEFAULT 'GENERAL'
                       CHECK (account_kind IN ('GENERAL', 'ISA', 'PENSION', 'IRP', 'OTHER')),
    -- 계좌번호는 끝 4자리만(****1234). 원번호는 저장하지 않는다(최소 수집)
    account_ref_masked VARCHAR(20),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_portfolio_accounts_user ON portfolio_accounts (user_id);

CREATE TABLE portfolio_transactions (
    id              BIGSERIAL     PRIMARY KEY,
    account_id      BIGINT        NOT NULL REFERENCES portfolio_accounts(id) ON DELETE CASCADE,
    type            VARCHAR(20)   NOT NULL
                    CHECK (type IN ('BUY', 'SELL', 'DIVIDEND', 'DEPOSIT', 'WITHDRAWAL', 'FEE', 'TAX')),
    trade_date      DATE          NOT NULL,
    -- 종목: stocks에 있으면 stock_id, 없으면(펀드·채권·매칭 전) 코드·이름만. 현금 거래는 둘 다 비어도 된다
    stock_id        BIGINT        REFERENCES stocks(id),
    instrument_code VARCHAR(30),
    instrument_name VARCHAR(200),
    -- 해외 소수점 주식을 위해 소수 6자리
    quantity        NUMERIC(24, 6),
    price           NUMERIC(24, 6),
    -- 거래 금액(수수료·세금 제외, 거래 통화). 매수·매도는 수량 × 단가, 그 밖은 입력값
    amount          NUMERIC(24, 4) NOT NULL CHECK (amount >= 0),
    fee             NUMERIC(24, 4) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    tax             NUMERIC(24, 4) NOT NULL DEFAULT 0 CHECK (tax >= 0),
    currency        VARCHAR(3)    NOT NULL DEFAULT 'KRW',
    memo            VARCHAR(200),
    -- 같은 거래가 두 번 들어오지 않게 한다(CSV를 다시 올려도). 손 입력은 매번 새 값이다
    fingerprint     VARCHAR(64)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_portfolio_tx_fingerprint UNIQUE (account_id, fingerprint),
    CONSTRAINT ck_portfolio_tx_trade CHECK (
        type NOT IN ('BUY', 'SELL')
        OR (quantity > 0 AND price >= 0 AND (stock_id IS NOT NULL OR instrument_code IS NOT NULL))
    ),
    CONSTRAINT ck_portfolio_tx_dividend CHECK (
        type <> 'DIVIDEND' OR stock_id IS NOT NULL OR instrument_code IS NOT NULL
    )
);

-- 보유 계산은 계좌의 거래를 날짜·입력 순으로 훑는다
CREATE INDEX idx_portfolio_tx_account_date ON portfolio_transactions (account_id, trade_date, id);
