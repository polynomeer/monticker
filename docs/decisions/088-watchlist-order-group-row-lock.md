# ADR-088: 관심종목 순서는 그룹 행 잠금 아래에서 0..n-1로 다시 매긴다

## Status
Accepted

## Context

관심종목 화면의 ⋯ 메뉴 "순서 이동"([design-rollout-plan.md](../design-rollout-plan.md) §2 /watchlist, P2)을 실제로 만들어야 했다.
`watchlist_items.sort_order` 컬럼은 V3부터 있었지만 항목 추가가 값을 채우지 않아 모두 0이었고, 화면은 추가 순(id)으로 보였다.

요구는 두 가지다.

- **같은 그룹을 동시에 고쳐도**(두 탭에서 연달아 위/아래로, 이동과 추가가 겹침) 순서 값이 겹치거나 빈칸이 남지 않는다.
- 남의 그룹·항목은 없는 것과 똑같은 404([security-review.md](../security-review.md) H6).

고려한 대안:

1. **이동 한 번을 CTE 한 문장으로**(`WITH ordered AS (... row_number() ...) UPDATE ...`) — 문장 하나라 원자적으로 보이지만,
   READ COMMITTED에서 CTE는 **문장 시작 시점 스냅샷**으로 읽는다. 다른 이동이 먼저 커밋되면 이 문장은 잠금을 기다린 뒤 갱신 대상 행만
   다시 평가하고 순서 계산은 옛 스냅샷 그대로 해 앞 요청의 결과를 덮어쓴다. 값이 겹칠 수 있다.
2. **`(group_id, sort_order)` UNIQUE DEFERRABLE 제약** — DB가 겹침을 막아 주지만 막을 뿐 직렬화하지는 않는다. 동시 이동은 커밋 시점에
   실패하고 재시도 루프가 필요하다. 다른 경로(예: 앞으로 생길 일괄 추가)가 순서를 채우지 않고 넣으면 그 경로가 깨진다.
3. **그룹 행 `SELECT ... FOR UPDATE` 후 별도 문장으로 읽고 다시 매기기** — 채택.
4. 분수 순번(`sort_order` 사이 값 끼워 넣기, LexoRank) — 재번호를 피하지만 그룹이 사용자당 수십 행이라 이득이 없고, 정밀도 소진·재정렬 배치가 생긴다.

## Decision

순서를 바꾸는 모든 쓰기(이동·추가·삭제)는 `WatchlistOrderRepository`를 거친다.

```
BEGIN
  SELECT g.id FROM watchlist_groups g JOIN watchlist_items i ON i.group_id = g.id
   WHERE i.id = :item AND g.user_id = :user FOR UPDATE OF g      -- 없거나 남의 것이면 0행 → 404
  SELECT id FROM watchlist_items WHERE group_id = :g ORDER BY sort_order, id   -- 새 문장 = 새 스냅샷
  (Kotlin) WatchlistOrdering.move(ids, item, target)              -- 범위 밖이면 맨 끝
  UPDATE watchlist_items w SET sort_order = v.ord - 1
    FROM unnest(:ids) WITH ORDINALITY v(id, ord) WHERE w.id = v.id AND w.group_id = :g AND w.sort_order <> v.ord - 1
COMMIT
```

- 추가는 같은 잠금 뒤 `MAX(sort_order) + 1`, 삭제는 잠금 → 삭제 → 남은 항목 재번호.
- API: `PATCH /api/watchlists/items/{itemId}/sort-order {"sortOrder": n}` → `{itemId, sortOrder}`(실제로 놓인 자리).
  `sortOrder`는 0 이상 정수(`@Min(0)`), 목록 길이를 넘으면 맨 끝.
- V85가 기존 행을 그룹마다 (sort_order, id) 순으로 0..n-1로 정규화하고 `(group_id, sort_order, id)` 인덱스를 만든다.
- 웹은 "내 순서" 정렬일 때만 위/아래 이동을 켜고, 시장 필터로 숨은 행은 건너뛰어 화면에서 보이는 이웃 자리로 옮긴다(낙관적 갱신, 실패 시 되돌림).

## Reasons

- 같은 그룹 요청이 잠금에서 한 줄로 서고, 잠금 뒤 읽기는 새 스냅샷이라 앞 요청 결과 위에서 계산한다. 재시도 루프가 필요 없다.
- 매번 그룹 전체를 0..n-1로 다시 매겨 겹침·빈칸이 원리상 남지 않는다(예전 데이터의 0 중복도 첫 쓰기에서 풀린다).
- 소유권 확인을 잠금 쿼리의 `user_id` 조건에 넣어 "남의 것"과 "없는 것"이 같은 경로(0행 → 404)를 탄다.
- 통합 테스트(`WatchlistOrderConcurrencyIntegrationTest`)가 8스레드 × 25회 무작위 이동 뒤 0..n-1을 확인한다. `FOR UPDATE`를 지우면
  이 테스트가 실패하는 것을 확인했다.

## Consequences

- 같은 그룹 쓰기는 직렬화된다. 그룹은 한 사용자의 것이고 쓰기 빈도가 사람 손 속도라 경합 비용은 무시할 만하다.
- 순서는 DB 제약이 아니라 이 저장소 경로가 지킨다. `watchlist_items`에 직접 INSERT/UPDATE하는 새 코드는 같은 잠금을 쓰거나
  `WatchlistService`를 거쳐야 한다(그러지 않으면 0 중복이 다시 생기고, 다음 이동 때 id 순으로 풀린다).
- 이동 한 번에 그룹 행 수만큼 UPDATE가 나갈 수 있다(바뀐 행만). 그룹당 수십 행 규모에서만 맞는 선택이다.
- 그룹 자체의 순서 이동(`watchlist_groups.sort_order`)은 아직 없다. 같은 방식(사용자 단위 잠금)으로 붙일 수 있다.

## Revisit When

- 그룹당 항목이 수백~수천으로 커져 재번호 UPDATE가 부담이 될 때 → 분수 순번 검토.
- 일괄 추가·가져오기처럼 이 경로 밖에서 항목을 쓰는 기능이 생길 때 → UNIQUE DEFERRABLE 제약을 함께 거는 것을 검토.
- 수평 확장으로 다른 저장소(예: 캐시된 순서)를 함께 갱신해야 할 때.
