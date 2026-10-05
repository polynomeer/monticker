-- 제작자 대시보드 월별·이번 달 집계(creator_id + earned_at 범위). 기존 (creator_id, status) 인덱스로는 기간 조건을 못 쓴다.
CREATE INDEX IF NOT EXISTS idx_creator_earnings_creator_earned
    ON creator_earnings (creator_id, earned_at);
