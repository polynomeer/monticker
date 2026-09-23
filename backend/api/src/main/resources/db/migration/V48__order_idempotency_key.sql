-- ADR-051 — 주문 제출의 멱등 키. 같은 키로 두 번 제출하면 두 번째는 새 주문을 만들지 않고
-- 첫 주문을 그대로 돌려준다.
--
-- 기존 멱등성(ADR-007)은 `X-Idempotency-Key` HTTP 필터라 **바깥에서 들어온 요청**만 보호한다.
-- 서버 내부에서 이벤트를 소비해 주문을 내는 경로(watch rule)는 그 필터를 타지 않는데, 아웃박스는
-- at-least-once 라 같은 이벤트가 재전달될 수 있다 — 필터 밖에 중복 체결 경로가 생긴다.
-- 키를 주문 테이블에 두면 애플리케이션이 죽는 시점과 무관하게 DB가 중복을 막는다.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

-- 부분 유니크 — 키 없는 주문(사용자가 화면에서 직접 낸 주문)은 제약을 받지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_idempotency_key
    ON orders (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
