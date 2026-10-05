-- ADR-069 — 섹터 집중도 한도(SectorConcentrationRule). NULL = 미설정(규칙을 평가하지 않는다) — 기존 사용자의 동작은 그대로다.
ALTER TABLE risk_limits ADD COLUMN sector_concentration_limit_pct NUMERIC(5,2);
ALTER TABLE risk_limits ADD CONSTRAINT chk_risk_limits_sector_pct
    CHECK (sector_concentration_limit_pct IS NULL OR (sector_concentration_limit_pct > 0 AND sector_concentration_limit_pct <= 100));
