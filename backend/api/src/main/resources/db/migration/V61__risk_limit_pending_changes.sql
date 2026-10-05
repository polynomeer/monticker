-- ADR-069 — 한도 완화 쿨링오프. 강화는 risk_limits에 바로 쓰고, 완화(한도 올리기·섹터 한도 해제·리스크 체크 끄기)는
-- 여기 두었다가 effective_at(요청 + 24시간)부터 적용한다. 항목마다 대기 중인 변경은 하나다(PK).
-- new_value: 정수 항목은 정수, is_active는 1/0, 섹터 한도 해제는 NULL.
CREATE TABLE risk_limit_pending_changes (
    user_id      BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    field        VARCHAR(40)   NOT NULL,
    new_value    NUMERIC(9,2),
    requested_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    effective_at TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (user_id, field)
);
