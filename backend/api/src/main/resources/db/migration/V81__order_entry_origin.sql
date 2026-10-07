-- ADR-085 — 모의 주문·체결의 진입 출처(왜 이 주문이 나갔는가).
-- 주문을 내는 서버 경로가 정한다: MANUAL(화면) · WATCH_RULE(ADR-051) · CONDITIONAL(ADR-075) · STRATEGY(자리만, 아직 경로 없음).
-- origin_ref: WATCH_RULE → watch_rules.id, CONDITIONAL → paper_conditional_orders.id, STRATEGY → 룰셋 id, MANUAL → NULL.
-- FK를 걸지 않는다 — 규칙·조건부 주문을 지워도 과거 체결의 출처 기록은 남아야 한다(원장과 같은 이유, ADR-013).
-- NULL은 "판정할 수 없음"이다(V82 백필 참고). 새 행은 항상 채워진다.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS origin     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS origin_ref BIGINT;
ALTER TABLE orders
    ADD CONSTRAINT ck_orders_origin
        CHECK (origin IS NULL OR origin IN ('MANUAL', 'WATCH_RULE', 'CONDITIONAL', 'STRATEGY'));

ALTER TABLE paper_trades
    ADD COLUMN IF NOT EXISTS origin     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS origin_ref BIGINT;
ALTER TABLE paper_trades
    ADD CONSTRAINT ck_paper_trades_origin
        CHECK (origin IS NULL OR origin IN ('MANUAL', 'WATCH_RULE', 'CONDITIONAL', 'STRATEGY'));

COMMENT ON COLUMN orders.origin IS 'ADR-085 진입 출처. 서버 제출 경로가 정한다(클라이언트 입력 아님). NULL = 판정 불가(V81 이전 행).';
COMMENT ON COLUMN paper_trades.origin IS 'ADR-085 진입 출처 — 체결을 만든 orders.origin의 사본. NULL = 판정 불가.';

-- 규칙 경유 손익(출처별 실현 손익) 조회 — 사용자별 출처 필터
CREATE INDEX IF NOT EXISTS idx_paper_trades_user_origin
    ON paper_trades (user_id, origin, origin_ref) WHERE origin IS NOT NULL AND origin <> 'MANUAL';
