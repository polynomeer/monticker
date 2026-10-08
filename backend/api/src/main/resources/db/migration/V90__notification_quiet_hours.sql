-- ADR-093: 방해 금지 시간(quiet hours). 시각은 KST 벽시계 시각이다(한국은 서머타임이 없어 하루가 늘 24시간) — 자정을 넘을 수
-- 있다(22:00~07:00). 켜져 있고 지금이 그 구간이면 worker 발송 필터가 끌 수 있는 알림의 **푸시만** 보내지 않는다(미루지 않는다).
-- 끌 수 없는 알림(결과 확인 중·조건부 주문 실패·리스크 경고)은 그대로 즉시 보낸다. 이메일·알림 이력은 그대로다.
-- 기본은 꺼짐 — 기존 사용자의 동작을 바꾸지 않는다(PG11+ fast default, 테이블 재작성 없음).
-- worker는 이 컬럼이 없는 스키마(api 배포 전)에서도 돈다 — 없으면 꺼짐으로 읽는다(NotificationPreferenceReader).
ALTER TABLE notification_preferences
    ADD COLUMN quiet_hours_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN quiet_hours_start   TIME    NOT NULL DEFAULT '22:00',
    ADD COLUMN quiet_hours_end     TIME    NOT NULL DEFAULT '07:00';

-- 시작=끝은 "0분"인지 "하루 종일"인지 모호하다 — 저장하지 않는다(api도 400). 분 단위만 받는다.
ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_quiet_hours_chk CHECK (
        quiet_hours_start <> quiet_hours_end
        AND EXTRACT(SECOND FROM quiet_hours_start) = 0
        AND EXTRACT(SECOND FROM quiet_hours_end) = 0
    );
