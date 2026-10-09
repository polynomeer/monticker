-- flyway:executeInTransaction=false
-- V98 후속 — 중복 인덱스 정리 1/2.
--
-- idx_paper_trades_user (user_id, traded_at DESC)(V11)는 idx_paper_trades_user_traded(V25)와 컬럼·순서가 같은 쌍둥이다.
-- 플래너는 둘 중 하나만 쓰고, 나머지는 체결 리스너의 INSERT마다 갱신 비용과 디스크만 먹는다. V25 쪽을 남긴다.
-- 근거: PaperTradesIndexPlanIntegrationTest(정리 전후 EXPLAIN — 어떤 쿼리도 Seq Scan으로 떨어지지 않는다).
--
-- paper_trades는 체결 리스너가 계속 쓰는 테이블이라 CONCURRENTLY로 지운다 — 그래서 이 스크립트는 트랜잭션 밖에서 돌고
-- 문장이 하나뿐이다(V84·V98 참고). 중간에 실패하면 인덱스가 INVALID로 남을 수 있다 — 같은 문장을 다시 실행한 뒤 `flyway repair`.
DROP INDEX CONCURRENTLY IF EXISTS idx_paper_trades_user;
