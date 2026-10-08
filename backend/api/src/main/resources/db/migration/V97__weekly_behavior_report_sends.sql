-- ADR-101 — 주간 투자 행동 리포트(매주 월요일 · 이메일)의 (사용자, 주) 한 번 보장.
-- 발송 전에 이 행을 "선점"한 인스턴스만 메일을 보낸다(INSERT … ON CONFLICT). api 인스턴스가 여러 대이거나 재시작해도
-- 같은 (user_id, week_start)는 한 번만 보낸다 — risk_limit_warnings(ADR-070)와 같은 방식이다.
--   SENDING : 선점했다(메일 발송 중). 선점한 인스턴스가 발송 도중 죽으면 이 상태로 남고 **다시 보내지 않는다**
--             (메일이 이미 나갔는지 알 수 없다 — 중복보다 누락을 택한다).
--   SENT    : SMTP가 받았다.
--   FAILED  : SMTP가 거부했거나 연결이 안 됐다(보내지 않았음이 확실). attempts < 최대이고 next_attempt_at이 지나면 다시 선점한다.
--   SKIPPED : 선점 뒤 다시 보니 보낼 것이 없었다(그 사이 탈퇴·거래 기록 초기화 등).
-- week_start는 리포트 대상 주의 월요일(KST 날짜). 경계는 Kotlin(KstPeriod)이 계산한다.
-- 개인정보(이메일 주소·리포트 내용)는 남기지 않는다.
CREATE TABLE weekly_report_sends (
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    week_start      DATE         NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 1,
    claimed_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(200),
    PRIMARY KEY (user_id, week_start),
    CONSTRAINT chk_weekly_report_sends_status CHECK (status IN ('SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT chk_weekly_report_sends_monday CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    CONSTRAINT chk_weekly_report_sends_attempts CHECK (attempts >= 1)
);

-- 재시도 대상(FAILED) 조회와 오래된 기록 정리용
CREATE INDEX idx_weekly_report_sends_week_status ON weekly_report_sends (week_start, status);
