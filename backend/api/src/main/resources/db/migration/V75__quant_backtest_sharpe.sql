-- ADR-079 — 룰셋 백테스트 결과에 연환산 샤프(무위험 3%)를 저장한다. 이전 결과는 NULL로 둔다(재계산하지 않음).
ALTER TABLE quant_backtest_results ADD COLUMN sharpe NUMERIC(10,4);
