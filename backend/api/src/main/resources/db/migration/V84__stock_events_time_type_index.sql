-- ADR-087 — 기간별 이벤트 집계(GET /api/events/summary)용 인덱스.
--
-- 기존 인덱스는 모두 stock_id·event_type·importance_score가 선두라(V5) "어느 하루의 전 종목 이벤트를 유형별로 센다"는
-- event_time 범위 조건을 받쳐 주지 못해 stock_events 전체를 훑었다. event_time을 선두로 두고
-- event_type·stock_id를 뒤에 붙여 유형별 COUNT(*)·COUNT(DISTINCT stock_id)를 인덱스만으로 끝낸다(index-only scan).
-- 종목별 기간 건수(GET /api/events/counts)는 기존 idx_stock_events_stock_time(stock_id, event_time DESC)을 쓴다.
CREATE INDEX IF NOT EXISTS idx_stock_events_time_type_stock
    ON stock_events (event_time, event_type, stock_id);
