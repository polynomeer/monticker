-- 보안 리뷰(2026-10) — 리스크 설정 화면의 사전 점검(POST /api/risk/check)은 주문이 아니다. 감사 기록은 남기되
-- 차단 기록 목록·"이번 달 차단" 집계(RiskDecisionQueryService)에서 뺄 수 있도록 표시한다.
-- 상수 기본값이라 PG 11+에서 테이블 재작성 없이 메타데이터만 바뀐다.
ALTER TABLE risk_check_logs ADD COLUMN dry_run BOOLEAN NOT NULL DEFAULT false;
