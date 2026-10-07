-- flyway:executeInTransaction=false
-- ADR-087 — 기간별 이벤트 집계(GET /api/events/summary)용 인덱스.
--
-- 기존 인덱스는 모두 stock_id·event_type·importance_score가 선두라(V5) "어느 하루의 전 종목 이벤트를 유형별로 센다"는
-- event_time 범위 조건을 받쳐 주지 못해 stock_events 전체를 훑었다. event_time을 선두로 두고
-- event_type·stock_id를 뒤에 붙여 유형별 COUNT(*)·COUNT(DISTINCT stock_id)를 인덱스만으로 끝낸다(index-only scan).
-- 종목별 기간 건수(GET /api/events/counts)는 기존 idx_stock_events_stock_time(stock_id, event_time DESC)을 쓴다.
--
-- 운영 stock_events는 worker가 계속 쓰는 테이블이라 CONCURRENTLY로 만든다 — 일반 CREATE INDEX는 생성 내내 쓰기를 막는다.
-- CONCURRENTLY는 트랜잭션 안에서 실행할 수 없어 이 스크립트만 트랜잭션 밖에서 돈다(위 flyway 지시자, 문장도 하나뿐이어야 한다).
-- 생성이 중간에 실패하면 INVALID 인덱스가 남고 IF NOT EXISTS가 그것을 건너뛴다 — 재시도 전에
-- `DROP INDEX CONCURRENTLY IF EXISTS idx_stock_events_time_type_stock;` 후 `flyway repair`.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_events_time_type_stock
    ON stock_events (event_time, event_type, stock_id);
