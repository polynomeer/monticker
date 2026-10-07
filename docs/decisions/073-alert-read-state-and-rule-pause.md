# ADR-073: 알림 읽음 상태는 DB에, 규칙 "끄기"와 "삭제"를 구분

## Status
Accepted

## Context

/alerts 화면(ADR-066)에는 "읽지 않음" 탭·표시, "모두 읽음", 상단 "읽지 않음" 수, 규칙별 켜기/끄기 토글이 있다. 지금까지 둘 다 동작하지 않았다(design-rollout-plan §2).

**읽음 상태.** 알림 이력 화면은 ES `alert_histories` 인덱스를 검색한다(`/api/alerts/history/search`). 원본은 Postgres `alert_histories`이고 worker가 발송 후 ES에 색인한다(ADR-042 아웃박스). 읽음 상태를 둘 곳으로 두 가지를 봤다.
- (a) ES 문서에 `readAt`을 넣고 읽을 때마다 다시 색인 — 색인 경로(worker → Kafka → api 컨슈머)를 사용자 클릭마다 태워야 하고, 색인이 밀리면 방금 읽은 알림이 다시 "읽지 않음"으로 보인다.
- (b) Postgres에만 `read_at`을 두고 검색 결과에 id로 붙인다.

**켜기/끄기.** `DELETE /api/alerts/rules/{id}`가 `is_active=false`로 표시만 하고, 다시 켜는 API가 없었다. 다시 켜는 API를 그대로 추가하면 **사용자가 삭제한 규칙까지 되살릴 수 있다** — 지금은 "꺼짐"과 "삭제"가 같은 값이기 때문이다. 또 워커는 활성 규칙을 메모리 인덱스로 들고 있어(ADR-044) 변경이 전파돼야 한다.

## Decision

1. **읽음 상태는 Postgres `alert_histories.read_at`(V64)에만 둔다 (b).** 검색 결과(≤100건)에 `id IN (…)` + 소유자 조인 한 번으로 `readAt`을 붙인다. ES 문서는 바꾸지 않는다.
   - `POST /api/alerts/history/{id}/read` — 멱등. 내 이력이 아니면 404.
   - `POST /api/alerts/history/read-all?upTo=` — `upTo`(기본 지금, 미래면 지금으로 자름) 이전에 발동한 알림만. 화면을 연 뒤 새로 온 알림은 남긴다.
   - `GET /api/alerts/stats`에 `unread` 수를 더한다. 부분 인덱스(`WHERE read_at IS NULL`)를 둔다.
   - **배포 전 이력은 모두 읽음으로 채운다**(`read_at = triggered_at`). 그러지 않으면 배포 직후 모든 사용자의 과거 알림 전부가 "읽지 않음"으로 쌓인다.
2. **"끄기"와 "삭제"를 구분한다 — `alert_rules.deleted_at`(V65).** 삭제 = `is_active=false` + `deleted_at`, 끄기 = `is_active=false`만. 이전에 꺼진 규칙은 모두 DELETE(또는 V45 정리)로 꺼진 것이라 `deleted_at = updated_at`으로 채워 삭제로 본다.
   - `PATCH /api/alerts/rules/{id} {"isActive": bool}` — 삭제했거나 남의 규칙은 404. 다시 켤 때는 생성과 같은 조건 검사(`AlertRuleConditions.isValid` — 평가기가 없는 유형, 조건 필드 누락)를 하고 통과 못 하면 409.
   - 변경은 **조건부 UPDATE 한 문장**(`WHERE id=? AND user_id=? AND deleted_at IS NULL`)으로 한다. 엔티티를 읽어 `save()`하면 그 사이 다른 요청이 삭제한 규칙의 `deleted_at`을 null로 덮어써 되살릴 수 있다. 0행이면 404.
   - `GET /api/alerts/rules`는 기본이 지금처럼 켜진 규칙만이고(모바일·관심종목 화면), `includePaused=true`일 때만 꺼 둔 규칙을 함께 준다.
3. **워커 인덱스(ADR-044) 일관성**: 켜기/끄기·삭제 모두 커밋 후 `alert:rules:changed`에 종목 id를 발행하고(기존 경로), `updated_at`을 갱신해 5분 보정 재로드(`syncDelta`)에도 잡힌다. 워커는 `is_active = true`만 적재하므로 바꿀 것이 없다.

## Reasons

- **(b)를 고른 이유**: 읽음은 사용자 한 명의 상태라 검색 대상이 아니다. 색인 지연이 UI 정합성을 깨면 안 되고, 클릭마다 색인 이벤트를 만들 이유가 없다. 붙이는 비용은 페이지당 쿼리 하나다.
- **`deleted_at`을 따로 두는 이유**: "삭제한 것은 돌아오지 않는다"는 사용자 기대를 데이터로 보장한다. 플래그 하나로 두 의미를 쓰면 재활성화 API가 곧 복구 API가 된다.
- **조건부 UPDATE**: 같은 행을 바꾸는 두 요청(끄기/켜기 vs 삭제) 사이 경쟁에서 삭제가 이기게 하는 가장 단순한 방법이다. 락이나 버전 컬럼을 새로 만들 필요가 없다.

## Consequences

- ES 검색 필터로 "읽지 않음만"을 걸 수는 없다. 화면의 "읽지 않음" 탭은 최근 50건 안에서 거른다 — 개수(상단)는 DB 전체 기준이라 둘이 다를 수 있다.
- 배포 전 이력은 읽은 적이 없어도 읽음으로 보인다(의도).
- 삭제한 규칙은 다시 켤 수 없다 — 같은 조건을 새로 만들어야 한다.
- `GET /api/alerts/rules`의 기본 응답은 바뀌지 않았다. 꺼 둔 규칙을 보려는 클라이언트는 `includePaused=true`를 보내야 한다.

## Revisit When

- 알림 이력 화면에 서버 페이지네이션·"읽지 않음만" 서버 필터가 필요해질 때 — 그때는 DB 쿼리 경로나 ES 문서의 읽음 필드를 다시 검토한다.
- 퀀트 시그널 알림을 알림 이력에 적재할 때(design-rollout-plan P2) — 같은 읽음 모델을 쓴다.
- 삭제한 규칙 복구("휴지통")가 필요해질 때.
