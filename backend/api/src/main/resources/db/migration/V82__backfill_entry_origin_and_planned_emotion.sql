-- ADR-085 — 기존 행의 진입 출처 백필과 감정 태그 "계획대로" 이관.
-- 모든 문장은 origin IS NULL 행만(또는 아직 이관하지 않은 태그만) 건드린다 — 다시 실행해도 결과가 같다(통합 테스트가 재실행한다).
--
-- 판정 근거는 "서버가 남긴 링크"만 쓴다. orders.idempotency_key의 접두사(WR:/PCO:)는 쓰지 않는다:
-- /api/matching/orders가 이 키를 요청 본문에서 받던 시절이 있어(ADR-085 Context) 사용자가 위조할 수 있었다.

-- 1) Watch Rule 발동 기록이 가리키는 주문(ADR-051). 같은 사용자의 주문만.
UPDATE orders o
   SET origin = 'WATCH_RULE', origin_ref = e.watch_rule_id
  FROM watch_rule_executions e
 WHERE e.order_id = o.id
   AND e.user_id = o.user_id
   AND e.status = 'EXECUTED'
   AND o.origin IS NULL;

-- 2) 모의 조건부 주문이 발동해 낸 주문(ADR-075, executed_order_id). 같은 사용자의 주문만.
UPDATE orders o
   SET origin = 'CONDITIONAL', origin_ref = c.id
  FROM paper_conditional_orders c
 WHERE c.executed_order_id = o.id
   AND c.user_id = o.user_id
   AND o.origin IS NULL;

-- 3) 멱등 키가 없는 주문 = 화면에서 직접 낸 주문. 서버 내부 경로(Watch Rule·조건부)는 항상 키를 붙였다(ADR-051).
--    키가 있는데 1)·2)에 걸리지 않은 주문은 출처를 단정할 수 없어 NULL로 둔다(화면 "—").
UPDATE orders
   SET origin = 'MANUAL', origin_ref = NULL
 WHERE origin IS NULL
   AND idempotency_key IS NULL;

-- 4) 체결 기록(paper_trades)은 체결을 만든 주문의 출처를 따른다(ADR-047 fill_id → fills.order_id).
UPDATE paper_trades pt
   SET origin = o.origin, origin_ref = o.origin_ref
  FROM fills f
  JOIN orders o ON o.id = f.order_id
 WHERE f.id = pt.fill_id
   AND o.user_id = pt.user_id
   AND o.origin IS NOT NULL
   AND pt.origin IS NULL;

-- 5) fill_id가 없는 거래는 ADR-047 이전 구 페이퍼 경로(/api/paper/buy|sell 직접 기록)였다 — 그 경로는 화면 주문뿐이었다.
UPDATE paper_trades
   SET origin = 'MANUAL', origin_ref = NULL
 WHERE origin IS NULL
   AND fill_id IS NULL;

-- 6) 감정 태그 — 웹이 "계획대로"를 OTHER + 메모 "계획대로"로 저장해 왔다(EmotionType에 값이 없어서). PLANNED로 옮기고
--    태그 이름과 같던 메모는 지운다. 사용자가 다른 내용을 덧붙인 메모는 OTHER 그대로 둔다(의도를 추측하지 않는다).
--    emotion은 VARCHAR(30)이고 CHECK가 없어 스키마 변경은 필요 없다(V14).
UPDATE order_emotion_tags
   SET emotion = 'PLANNED', memo = NULL
 WHERE emotion = 'OTHER'
   AND btrim(memo) = '계획대로';
