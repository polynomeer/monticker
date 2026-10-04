-- ADR-056 — 실거래 주문의 결과 불명을 일급 상태로.
--
-- PENDING_SUBMIT: 주문 의도를 브로커 호출 전에 커밋한 상태. 호출 결과를 아직 기록하지 못했다
--                 (호출 중이거나, 호출 직후 프로세스가 죽었다).
-- UNKNOWN       : 요청은 나갔는데 확정 응답을 못 받았다(읽기 타임아웃·5xx 등). 증권사에서 체결됐을 수 있다.
-- 두 상태 모두 BrokerageOrderReconciler가 증권사 당일 주문 목록과 대조해 해소한다. 재주문은 하지 않는다.
ALTER TABLE brokerage_orders DROP CONSTRAINT IF EXISTS brokerage_orders_status_check;
ALTER TABLE brokerage_orders ADD CONSTRAINT brokerage_orders_status_check
    CHECK (status IN ('PENDING_SUBMIT','SUBMITTED','UNKNOWN','FILLED','PARTIALLY_FILLED','CANCELLED','REJECTED'));

-- 우리가 만든 주문 식별자. Toss에는 clientOrderId(멱등 키)로 보낸다. 조건부 주문은 co-<id>로 결정적이라
-- 같은 조건부 주문이 두 경로로 발동돼도 두 번째 INSERT가 여기서 막힌다. 기존 행은 NULL.
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS client_order_id VARCHAR(36);
CREATE UNIQUE INDEX IF NOT EXISTS ux_brokerage_orders_client_order_id
    ON brokerage_orders (client_order_id)
    WHERE client_order_id IS NOT NULL;

-- 대조 이력. needs_review: 매칭 후보가 2건 이상이라 자동으로 고를 수 없다 — 사람이 본다.
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS reconcile_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS needs_review BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(20)
    CHECK (resolved_by IS NULL OR resolved_by IN ('BROKER_LOOKUP','NOT_FOUND'));

-- 다음 대조 시각. 해소되지 않는 행(매칭 모호·연동 끊긴 계좌)이 배치 상한을 영구히 차지해 새 결과 불명 주문이
-- 대조되지 않는 고갈을 막는다 — 시도할 때마다 뒤로 미룬다(지수 백오프, 상한 10분). NULL = 즉시 대상.
ALTER TABLE brokerage_orders ADD COLUMN IF NOT EXISTS next_reconcile_at TIMESTAMPTZ;

-- 대조 잡의 스캔 대상만 담는 작은 부분 인덱스.
CREATE INDEX IF NOT EXISTS idx_brokerage_orders_unresolved
    ON brokerage_orders (next_reconcile_at NULLS FIRST, submitted_at)
    WHERE status IN ('PENDING_SUBMIT','UNKNOWN');

-- 리밸런싱 leg도 결과 불명을 그대로 기록한다. FAILED로 남기면 실제로 체결된 leg가 실패로 보인다.
-- 해소 결과는 executed_order_id가 가리키는 brokerage_orders 행에 있다.
ALTER TABLE rebalance_execution_legs DROP CONSTRAINT IF EXISTS rebalance_execution_legs_status_check;
ALTER TABLE rebalance_execution_legs ADD CONSTRAINT rebalance_execution_legs_status_check
    CHECK (status IN ('EXECUTED','FAILED','UNKNOWN'));
