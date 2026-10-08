-- ADR-089: 모의 계좌 시작 자금 선택(온보딩)과 사용자 관심 분야·사용 방식 저장.

-- 1) 모의 계좌의 시작 자금. 지금까지는 상수 1,000만원(Money.INITIAL_BALANCE)이었고 원장 대사(ADR-043)가 그 상수를 더했다.
--    시작 자금을 고를 수 있게 되면 대사의 "초기 지급" 항이 계좌마다 달라지므로 계좌 행에 둔다.
--    기존 행은 모두 1,000만원으로 시작했으므로 DEFAULT로 채운다(PG11+ fast default — 테이블 재작성 없음).
--    허용값은 서버 화이트리스트(PaperInitialCapital)와 같아야 한다 — DB에서도 한 번 더 막는다.
ALTER TABLE paper_accounts
    ADD COLUMN initial_capital NUMERIC(18,4) NOT NULL DEFAULT 10000000;
ALTER TABLE paper_accounts
    ADD CONSTRAINT paper_accounts_initial_capital_chk
        CHECK (initial_capital IN (10000000, 30000000, 100000000));

-- 2) 온보딩 "관심 분야·사용 방식". 지금은 저장·조회만 한다(홈·알림 우선순위 반영은 후속).
--    값은 서버 enum 이름만 들어온다 — 화이트리스트를 DB에서도 강제한다(화면 문자열을 그대로 저장하지 않는다).
CREATE TABLE user_preferences (
    user_id          BIGINT       PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    interest_sectors VARCHAR(32)[] NOT NULL DEFAULT '{}',
    usage_style      VARCHAR(16),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT user_preferences_sectors_chk CHECK (
        cardinality(interest_sectors) <= 9
        AND interest_sectors <@ ARRAY['SEMICONDUCTOR', 'SECONDARY_BATTERY', 'INTERNET_PLATFORM', 'BIO', 'FINANCE',
                                      'AUTOMOTIVE', 'DIVIDEND', 'ETF', 'SHIPBUILDING_DEFENSE']::VARCHAR(32)[]
    ),
    CONSTRAINT user_preferences_style_chk CHECK (usage_style IS NULL OR usage_style IN ('OBSERVE', 'EVENT_TRADING', 'QUANT'))
);
