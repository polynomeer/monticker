-- ADR-053 — 정기결제 이중청구 방지.
--
-- 갱신 배치는 orderId를 `renewal_<subId>_<millis>`로 만들고 있었다. 타임스탬프가 들어가는
-- 순간 재시도마다 새 orderId가 되고, 토스는 orderId로 중복을 걸러내므로 그 방어가 통째로
-- 무력해진다 — 배치 재실행/타임아웃 재시도가 곧 이중청구였다.
--
-- 이제 orderId는 (구독, 청구주기)에서 결정적으로 유도되고, 그 값을 여기에 저장한다.
-- 유니크 인덱스는 "같은 주기에 두 번 청구 시도"를 프로세스 안의 검사가 아니라 DB에서 막는다
-- (orders.idempotency_key 와 같은 방식 — ADR-051).
ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS pg_order_id VARCHAR(100);

-- 부분 유니크: 일회성 결제(confirm 플로우)는 아직 orderId를 기록하지 않아 NULL이 남는다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_records_pg_order_id
    ON payment_records (pg_order_id)
    WHERE pg_order_id IS NOT NULL;
