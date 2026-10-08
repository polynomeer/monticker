-- flyway:executeInTransaction=false
-- 종목별 모의 체결 내역 인덱스 (PR #177 후속).
--
-- GET /api/paper/history?stockId=…&from=…&to=… 는 `user_id = ? AND stock_id = ? [AND traded_at 범위]
-- ORDER BY traded_at DESC, id DESC LIMIT/OFFSET`이다. 기존 인덱스는 (user_id, traded_at DESC)와 (user_id, stock_id)뿐이라
-- 어느 쪽을 골라도 사용자 전체 구간을 훑고 정렬했다(V11·V25). 정렬 키까지 담아 한 종목·구간만 순서대로 읽고 LIMIT에서 멈춘다.
-- 같은 모양을 쓰는 종목별 최근 매수 진입 출처(DISTINCT ON), 감정 태그의 "다음 매도가"(LATERAL), 실현손익 라인도 이 인덱스를 탄다.
--
-- 체결 리스너가 계속 쓰는 테이블이라 CONCURRENTLY로 만든다 — 그래서 이 스크립트는 트랜잭션 밖에서 돌고 문장이 하나뿐이다(V84 참고).
-- 생성이 중간에 실패하면 INVALID 인덱스가 남고 IF NOT EXISTS가 그것을 건너뛴다 — 재시도 전에
-- `DROP INDEX CONCURRENTLY IF EXISTS idx_paper_trades_user_stock_traded;` 후 `flyway repair`.
--
-- 이 인덱스가 앞머리 (user_id, stock_id)를 품으므로 idx_paper_trades_stock(V11)은 중복이 되고,
-- idx_paper_trades_user(V11)와 idx_paper_trades_user_traded(V25)는 컬럼·순서가 같은 쌍둥이다.
-- 두 DROP은 각각 별도의 단일 문장 CONCURRENTLY 마이그레이션으로 후속 처리한다(이 파일에 섞지 않는다).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_paper_trades_user_stock_traded
    ON paper_trades (user_id, stock_id, traded_at DESC, id DESC);
