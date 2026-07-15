-- RuleSet을 MongoDB로 이전: rule_set_id FK 제거 후 VARCHAR(24)로 변경

-- 1. quant_signals.rule_set_id FK 제거 후 VARCHAR로 변경
ALTER TABLE quant_signals DROP CONSTRAINT IF EXISTS quant_signals_rule_set_id_fkey;
ALTER TABLE quant_signals ALTER COLUMN rule_set_id TYPE VARCHAR(24) USING rule_set_id::TEXT;

-- 2. quant_backtest_results.rule_set_id FK 제거 후 VARCHAR로 변경
ALTER TABLE quant_backtest_results DROP CONSTRAINT IF EXISTS quant_backtest_results_rule_set_id_fkey;
ALTER TABLE quant_backtest_results ALTER COLUMN rule_set_id TYPE VARCHAR(24) USING rule_set_id::TEXT;

-- 3. rule_set_versions 삭제 (MongoDB 도큐먼트 내 embed로 대체)
DROP TABLE IF EXISTS rule_set_versions;

-- 4. rule_sets 삭제 (MongoDB collection으로 이전)
DROP TABLE IF EXISTS rule_sets;
