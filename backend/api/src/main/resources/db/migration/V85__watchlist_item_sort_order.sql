-- 관심종목 순서 이동(design-rollout-plan §2 /watchlist). 지금까지 항목 추가는 sort_order를 채우지 않아 모두 0이었다.
-- 그룹마다 지금 화면에 보이던 순서(추가 순 = id)대로 0..n-1을 매긴다. 이후 쓰기는 그룹 행 잠금 아래에서
-- 항상 0..n-1로 다시 매긴다(WatchlistOrderRepository).
UPDATE watchlist_items w
SET sort_order = o.pos
FROM (
    SELECT id, (row_number() OVER (PARTITION BY group_id ORDER BY sort_order, id) - 1)::int AS pos
    FROM watchlist_items
) o
WHERE w.id = o.id AND w.sort_order <> o.pos;

-- 그룹 안 순서대로 읽기(ORDER BY sort_order, id). 관심종목 테이블은 사용자당 수십 행이라 일반 CREATE INDEX로 충분하다.
CREATE INDEX IF NOT EXISTS idx_watchlist_items_group_sort ON watchlist_items (group_id, sort_order, id);
