-- ADR-071 — 시장 지수·환율 시세. worker(MarketIndexCollector)가 쓰고 api(GET /api/market/indices)가 읽는다.
-- 출처(source)와 모의 여부(is_mocked)를 행마다 남긴다: 실시세 연동 전에는 Mock 공급자 값이 들어오고,
-- 화면은 is_mocked=true인 값을 실데이터처럼 보여주지 않는다(모의 표시).

-- 지수별 최신 시세 한 행
CREATE TABLE market_index_quotes (
    code       VARCHAR(16)    PRIMARY KEY,           -- KOSPI | KOSDAQ | USDKRW
    name       VARCHAR(50)    NOT NULL,
    value      NUMERIC(18, 4) NOT NULL,
    prev_close NUMERIC(18, 4),                       -- 직전 거래일 종가(market_index_daily에서 계산)
    as_of      TIMESTAMPTZ    NOT NULL,              -- 시세 시각
    source     VARCHAR(20)    NOT NULL,              -- MOCK | KIS ...
    is_mocked  BOOLEAN        NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- 지수별 일별 종가(KST 거래일). 장중에는 당일 행이 최신 값으로 갱신되고, 장 마감 후 마지막 값이 종가로 남는다.
-- /compare 베타(KOSPI) 계산이 이 테이블을 쓴다.
CREATE TABLE market_index_daily (
    code       VARCHAR(16)    NOT NULL,
    trade_date DATE           NOT NULL,
    close      NUMERIC(18, 4) NOT NULL,
    is_mocked  BOOLEAN        NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (code, trade_date)
);
