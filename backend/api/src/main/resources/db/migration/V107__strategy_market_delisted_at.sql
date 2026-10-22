-- ADR-109 Note — 크리에이터가 탈퇴하면 마켓 전략을 판매 중지한다. 행은 지우지 않는다(creator_earnings가 참조, 5년 보존).
-- NULL이면 판매 중. 채워지면 마켓 목록·검색·총수·구독에서 빠진다(MARKET_VISIBLE_FROM, StrategyMarketController.subscribe).
ALTER TABLE strategy_market ADD COLUMN IF NOT EXISTS delisted_at TIMESTAMPTZ;
