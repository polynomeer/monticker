-- ADR-090 — 규칙 없이 생기는 알림 이력(첫 사례: 퀀트 시그널). 지금까지 alert_histories의 주인은 alert_rules를 거쳐서만
-- 정해졌다(rule_id NOT NULL). 시그널은 사용자가 만든 규칙이 아니라 "내 전략·구독 전략"이라는 관계에서 나오므로
-- 행이 직접 주인(user_id)을 갖는다. 한 행은 둘 중 정확히 한 길로만 주인을 갖는다.
--
-- alert_histories는 작은 테이블이고(사용자 수 × 쿨다운 10분), 아래 DDL은 모두 메타데이터 변경이거나 짧은 스캔이다.
ALTER TABLE alert_histories ALTER COLUMN rule_id DROP NOT NULL;
ALTER TABLE alert_histories ADD COLUMN user_id   BIGINT REFERENCES users(id);
-- 규칙이 없는 행의 종류(worker/api NotificationCategory 이름, 예: QUANT_SIGNAL). 규칙 행은 alert_rules.rule_type을 쓴다.
ALTER TABLE alert_histories ADD COLUMN category  VARCHAR(30);
-- 사건 하나 = 사용자당 한 행. 예: quant-signal:{quant_signals.id}
ALTER TABLE alert_histories ADD COLUMN dedup_key VARCHAR(120);

ALTER TABLE alert_histories ADD CONSTRAINT chk_alert_histories_owner CHECK (
    (rule_id IS NOT NULL AND user_id IS NULL AND category IS NULL AND dedup_key IS NULL)
    OR (rule_id IS NULL AND user_id IS NOT NULL AND category IS NOT NULL AND dedup_key IS NOT NULL)
);

-- 멱등 적재: 아웃박스 재전달·동시 리스너가 같은 신호를 두 번 써도 한 행(INSERT … ON CONFLICT DO NOTHING).
-- 사용자별 조회(읽지 않음·모두 읽음·통계)도 이 인덱스의 앞 열(user_id)을 탄다.
CREATE UNIQUE INDEX uq_alert_histories_user_dedup ON alert_histories (user_id, dedup_key) WHERE user_id IS NOT NULL;
