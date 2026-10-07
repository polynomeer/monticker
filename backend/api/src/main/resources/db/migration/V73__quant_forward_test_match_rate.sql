-- ADR-078 — 포워드 일치율. 일일 평가·중지 때마다 같은 기간 재실행 신호와 비교한 결과를 저장한다.
-- 목록 화면(룰셋 카드·전략 마켓 카드)이 카드마다 재실행하지 않도록 계산 결과를 행에 둔다.
ALTER TABLE quant_forward_tests
    ADD COLUMN match_rate         NUMERIC(5,4),
    ADD COLUMN matched_signals    INTEGER,
    ADD COLUMN compared_signals   INTEGER,
    ADD COLUMN match_evaluated_at TIMESTAMPTZ;
