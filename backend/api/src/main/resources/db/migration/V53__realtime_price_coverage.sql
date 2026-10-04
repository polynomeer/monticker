-- ADR-060 — 실시세(KIS·Toss)가 연결된 종목 집합. 시세 생산자 worker(RealtimeCoveragePublisher)가 기동 시와 60초마다 전체를 덮어쓴다.
-- api는 published_at이 5분 넘은 행을 인정하지 않는다(공표가 끊기면 "모름" — 조건부 주문 생성이 막힌다).
CREATE TABLE IF NOT EXISTS realtime_price_coverage (
    stock_id     BIGINT       PRIMARY KEY REFERENCES stocks(id),
    source       VARCHAR(10)  NOT NULL CHECK (source IN ('KIS','TOSS')),
    published_at TIMESTAMPTZ  NOT NULL
);
