-- 구독 전략 신호 이력 API가 신호 가격을 돌려줄 수 있도록 평가일 종가를 저장한다. 이전 신호는 NULL.
ALTER TABLE quant_signals ADD COLUMN price NUMERIC(18,4);

-- 이번 달 신호 수 집계(구독 전략 전체)·전략별 최근 신호 조회는 기존 idx_quant_signals_ruleset(rule_set_id, signal_time DESC)를 쓴다.
