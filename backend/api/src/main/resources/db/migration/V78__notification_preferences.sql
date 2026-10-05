-- ADR-082: 알림 설정을 Redis(notif:pref:{userId}, 만료 없음·아무도 읽지 않음)에서 Postgres로 옮긴다. worker 발송 경로가 읽는다.
-- 행이 없으면 기본값이다(아래 DEFAULT와 api NotificationPreferenceRequest 기본값이 같아야 한다). 기존 Redis 값은 api·worker가
-- 행이 없을 때만 읽는다(지연 이전) — 사용자가 한 번 저장하면 이 테이블로 옮겨진다.
-- '결과 확인 중'·조건부 주문 실패 알림은 끌 수 없다(ADR-056/065) — 그래서 여기 컬럼이 없다.
CREATE TABLE notification_preferences (
    user_id                      BIGINT      PRIMARY KEY REFERENCES users(id),
    all_enabled                  BOOLEAN     NOT NULL DEFAULT true,   -- 전체 알림(끄면 끌 수 있는 알림 전부 중지)
    push_enabled                 BOOLEAN     NOT NULL DEFAULT true,   -- 채널
    email_enabled                BOOLEAN     NOT NULL DEFAULT true,
    price_alert_push             BOOLEAN     NOT NULL DEFAULT true,   -- 알림 규칙(목표가·RSI·이동평균·보유 하락), 관심종목 급등락
    price_alert_email            BOOLEAN     NOT NULL DEFAULT false,
    volume_surge_push            BOOLEAN     NOT NULL DEFAULT true,   -- 거래량 급증(알림 규칙 VOLUME_SURGE, 관심종목 이벤트)
    volume_surge_email           BOOLEAN     NOT NULL DEFAULT false,
    news_alert_push              BOOLEAN     NOT NULL DEFAULT true,
    news_alert_email             BOOLEAN     NOT NULL DEFAULT false,
    quant_signal_push            BOOLEAN     NOT NULL DEFAULT true,   -- 내 포워드 테스트 신호
    quant_signal_email           BOOLEAN     NOT NULL DEFAULT false,
    fills_push                   BOOLEAN     NOT NULL DEFAULT true,   -- 실거래 체결·정산 완료
    fills_email                  BOOLEAN     NOT NULL DEFAULT false,
    strategy_market_news_push    BOOLEAN     NOT NULL DEFAULT false,  -- 광고성 — MARKETING 동의도 있어야 보낸다(ADR-068)
    strategy_market_news_email   BOOLEAN     NOT NULL DEFAULT false,
    weekly_report_email          BOOLEAN     NOT NULL DEFAULT true,
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
