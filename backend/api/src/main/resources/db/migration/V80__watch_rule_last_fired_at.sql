-- 보안 리뷰(2026-10) — watch rule 쿨다운을 원자적으로 판정한다(ADR-077 Note).
-- 예전 쿨다운은 "최근 cooldown_sec 안에 EXECUTED 기록이 있는가"를 조회한 뒤 주문했다. 기록은 주문 체결 뒤에야
-- 생기므로, 같은 규칙에 서로 다른 이벤트 두 개가 동시에 오면 둘 다 조회를 통과해 두 번 체결됐다.
-- 이제 발동은 이 행을 FOR UPDATE로 잠근 트랜잭션 안에서 "쿨다운 판정 + 하루 슬롯 + last_fired_at 기록"을 한 번에 한다.
-- 주문이 나가지 않으면(거부·인프라 오류) 이전 값으로 되돌린다 — 쿨다운은 여전히 체결된 발동만 센다.
ALTER TABLE watch_rules ADD COLUMN IF NOT EXISTS last_fired_at TIMESTAMPTZ;

-- 배포 직후에도 진행 중이던 쿨다운이 이어지도록 마지막 체결 시각으로 채운다.
UPDATE watch_rules w SET last_fired_at = e.last_executed
FROM (
    SELECT watch_rule_id, max(created_at) AS last_executed
    FROM watch_rule_executions WHERE status = 'EXECUTED'
    GROUP BY watch_rule_id
) e
WHERE e.watch_rule_id = w.id;
