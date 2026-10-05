-- ADR-078 — 룰셋 목록·전략 마켓 목록이 룰셋별 최신 백테스트 1건을 DISTINCT ON으로 한 번에 읽는다.
-- 기존 idx_quant_backtest_ruleset(rule_set_id)만으로는 created_at 정렬을 위해 룰셋별 결과를 모두 정렬해야 한다.
CREATE INDEX idx_quant_backtest_ruleset_created
    ON quant_backtest_results (rule_set_id, created_at DESC, id DESC);
