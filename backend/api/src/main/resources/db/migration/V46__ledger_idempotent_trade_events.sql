-- L-05에서 발견: paper 체결 원장(recordBuy/recordSell)이 Modulith 아웃박스(@ApplicationModuleListener)의
-- at-least-once 재전달에 멱등하지 않아, 커넥션 풀 고갈 등으로 리스너 tx 완료 표시가 실패하면 5분 뒤 재시도에서
-- 같은 체결의 FILL/SETTLEMENT 원장이 중복 기록됐다 → 원장이 실제 현금과 어긋나 대사(ADR-043) 드리프트 발생.

-- 1) 기존 중복 제거: (paper_trade_id, event_type)마다 가장 이른 id 하나만 남긴다. 중복은 재전달로 생긴 잘못된 행이라
--    삭제가 곧 교정이다(현금 컬럼은 첫 기록 때만 움직였고 중복은 원장에만 쌓였다).
DELETE FROM ledger_events e
USING ledger_events keep
WHERE e.paper_trade_id IS NOT NULL
  AND e.paper_trade_id = keep.paper_trade_id
  AND e.event_type     = keep.event_type
  AND e.id > keep.id;

-- 2) 백스톱: 체결 1건당 유형별 원장 1행. 애플리케이션 멱등 체크(LedgerService)와 이중 방어이며 경합도 막는다.
--    settlement/reset 등 paper_trade_id 가 없는 이벤트는 대상에서 제외(부분 인덱스).
CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_events_trade_type
    ON ledger_events (paper_trade_id, event_type)
    WHERE paper_trade_id IS NOT NULL;
