-- ADR-086 — KRX 거래일 캘린더의 단일 출처.
--
-- 예전에는 영업일 계산이 "토·일만 건너뜀"이라 공휴일 직전 체결의 T+2 정산일이 휴장일에 잡혔다.
-- api(정산 T+2·장 상태 API)와 worker(MarketSchedule·지수 수집기)가 모두 이 테이블을 읽는다.
--
-- market_holidays      : 평일인데 장이 열리지 않는 날만 둔다(토·일은 규칙으로 처리 — CHECK로 강제).
-- market_calendar_years: 어느 해까지 캘린더를 채웠는지. 여기 없는 해는 "모름"이다 — 코드는 그 해를
--                        주말만 건너뛰는 규칙으로 계산하면서 경고 로그·메트릭을 남기고 calendarCoverageUntil로 노출한다.
--                        (공휴일 행이 하나라도 있다고 그 해를 다 채운 것으로 보면, 임시공휴일 한 건만 미리 넣어도
--                         나머지 공휴일이 조용히 영업일이 된다. 그래서 커버리지를 따로 둔다.)
--
-- !! 운영 전 확인 필수 !!
-- 아래 시드는 「공휴일에 관한 법률」·「관공서의 공휴일에 관한 규정」의 규칙(설·추석 3일, 대체공휴일,
-- 선거일, 노동절, 연말 휴장)으로 유도한 값이다. 2026년은 언론 보도의 KRX 휴장일(17일)과 대조했지만
-- KRX 공식 연간 휴장일 공고와는 아직 대조하지 않았다. 2027년은 공고 전이다. verified=false가 그 표시다.
-- 공식 공고와 대조한 뒤에는 후속 마이그레이션으로 source='KRX_NOTICE', verified=true로 바꾼다.
-- 임시공휴일(정부가 수 일~수 주 전에 지정)도 후속 마이그레이션으로 넣는다. 갱신 절차는 ADR-086 참고.

CREATE TABLE IF NOT EXISTS market_holidays (
    market        VARCHAR(16)  NOT NULL DEFAULT 'KRX',
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(100) NOT NULL,
    -- SEED_RULE_DERIVED(규칙으로 유도) | KRX_NOTICE(KRX 공고와 대조) | TEMPORARY_HOLIDAY(임시공휴일)
    source        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (market, holiday_date),
    CONSTRAINT chk_market_holidays_weekday CHECK (EXTRACT(ISODOW FROM holiday_date) BETWEEN 1 AND 5)
);

CREATE TABLE IF NOT EXISTS market_calendar_years (
    market      VARCHAR(16) NOT NULL DEFAULT 'KRX',
    year        INTEGER     NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (market, year)
);

INSERT INTO market_calendar_years (market, year, verified, note) VALUES
    ('KRX', 2026, FALSE, '규칙 유도 + 언론 보도 KRX 휴장일 17일과 대조. KRX 공식 공고 대조 전'),
    ('KRX', 2027, FALSE, '규칙 유도. KRX 공고 전 — 2026년 말 공고와 반드시 대조')
ON CONFLICT (market, year) DO NOTHING;

INSERT INTO market_holidays (market, holiday_date, name, source) VALUES
    -- 2026
    ('KRX', DATE '2026-01-01', '신정',                        'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-02-16', '설날 연휴',                   'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-02-17', '설날',                        'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-02-18', '설날 연휴',                   'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-03-02', '삼일절 대체공휴일',           'SEED_RULE_DERIVED'),   -- 3/1 일요일
    ('KRX', DATE '2026-05-01', '노동절(근로자의 날)',         'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-05-05', '어린이날',                    'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-05-25', '부처님오신날 대체공휴일',     'SEED_RULE_DERIVED'),   -- 5/24 일요일
    ('KRX', DATE '2026-06-03', '제9회 전국동시지방선거',      'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-07-17', '제헌절',                      'SEED_RULE_DERIVED'),   -- 2026년부터 공휴일 재지정
    ('KRX', DATE '2026-08-17', '광복절 대체공휴일',           'SEED_RULE_DERIVED'),   -- 8/15 토요일
    ('KRX', DATE '2026-09-24', '추석 연휴',                   'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-09-25', '추석',                        'SEED_RULE_DERIVED'),   -- 9/26(토) 연휴는 주말, 일요일과 겹치지 않아 대체 없음
    ('KRX', DATE '2026-10-05', '개천절 대체공휴일',           'SEED_RULE_DERIVED'),   -- 10/3 토요일
    ('KRX', DATE '2026-10-09', '한글날',                      'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-12-25', '성탄절',                      'SEED_RULE_DERIVED'),
    ('KRX', DATE '2026-12-31', '연말 휴장일',                 'SEED_RULE_DERIVED'),
    -- 2027 (KRX 공고 전 — 규칙 유도)
    ('KRX', DATE '2027-01-01', '신정',                        'SEED_RULE_DERIVED'),
    ('KRX', DATE '2027-02-08', '설날 연휴',                   'SEED_RULE_DERIVED'),   -- 설날 2/7(일), 연휴 2/6(토)~2/8(월)
    ('KRX', DATE '2027-02-09', '설날 대체공휴일',             'SEED_RULE_DERIVED'),   -- 연휴가 일요일과 겹침
    ('KRX', DATE '2027-03-01', '삼일절',                      'SEED_RULE_DERIVED'),
    ('KRX', DATE '2027-05-05', '어린이날',                    'SEED_RULE_DERIVED'),   -- 노동절 5/1은 토요일(대체 없음)
    ('KRX', DATE '2027-05-13', '부처님오신날',                'SEED_RULE_DERIVED'),
    ('KRX', DATE '2027-07-19', '제헌절 대체공휴일',           'SEED_RULE_DERIVED'),   -- 7/17 토요일. 현충일 6/6(일)은 대체 대상 아님
    ('KRX', DATE '2027-08-16', '광복절 대체공휴일',           'SEED_RULE_DERIVED'),   -- 8/15 일요일
    ('KRX', DATE '2027-09-14', '추석 연휴',                   'SEED_RULE_DERIVED'),
    ('KRX', DATE '2027-09-15', '추석',                        'SEED_RULE_DERIVED'),
    ('KRX', DATE '2027-09-16', '추석 연휴',                   'SEED_RULE_DERIVED'),
    ('KRX', DATE '2027-10-04', '개천절 대체공휴일',           'SEED_RULE_DERIVED'),   -- 10/3 일요일
    ('KRX', DATE '2027-10-11', '한글날 대체공휴일',           'SEED_RULE_DERIVED'),   -- 10/9 토요일
    ('KRX', DATE '2027-12-27', '성탄절 대체공휴일',           'SEED_RULE_DERIVED'),   -- 12/25 토요일
    ('KRX', DATE '2027-12-31', '연말 휴장일',                 'SEED_RULE_DERIVED')
ON CONFLICT (market, holiday_date) DO NOTHING;
