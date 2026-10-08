# ADR-089: 모의 계좌 시작 자금은 계좌 행에 두고 처음 생성 때만 화이트리스트로 정한다 · 온보딩 선택은 저장만 한다

## Status
Accepted

## Context

온보딩 2단계(docs/design-rollout-plan.md §6)에는 "모의투자 시작 자금 1,000만·3,000만·1억"과 "관심 분야·사용 방식"이 있었지만
1,000만 외에는 비활성이었고 선택은 저장되지 않았다.

시작 자금은 단순한 UI 옵션이 아니다.

- 모의 계좌는 첫 주문 때 지연 생성되고(`PaperTradingService.getOrCreateAccount`, `OrderSagaOrchestrator`의
  `INSERT … ON CONFLICT DO NOTHING`) 잔고는 상수 `Money.INITIAL_BALANCE`(1,000만)에서 시작한다.
- 원장 대사(ADR-043)의 불변식 `cash + reserved = INITIAL_BALANCE + Σ원장`이 **같은 상수**를 더한다. 시작 자금이 1억인 계좌를
  만들면서 대사를 그대로 두면 그 계좌는 매일 +9,000만 "불일치"로 페이지 알람이 울린다.
- 초기화(`/api/paper/reset`)도 상수로 되돌린다.

고려한 대안:

1. **시작 자금을 원장 DEPOSIT으로 기록** — 계좌 생성 시 1,000만 초과분을 입금 이벤트로 남긴다. 상수 항은 그대로 둘 수 있지만
   "입금"이 아닌 것을 입금으로 기록하게 되고(지갑 화면·행동 점수가 입금으로 셈), 계좌 생성과 원장 기록이 다른 트랜잭션
   (원장은 비동기 리스너)이라 그 사이의 대사가 불일치로 보인다.
2. **계좌 행에 `initial_capital` 컬럼** — 채택. 대사의 초기 지급 항을 계좌별 값으로 바꾼다. 기존 행은 DEFAULT 1,000만이라
   기존 대사 결과가 바뀌지 않는다.
3. **임의 금액 허용(상한만)** — 화면은 세 버튼뿐이고, 임의 금액은 리스크 한도(현금 대비 비율)·리더보드 비교를 흐린다. 화이트리스트로 충분하다.

관심 분야·사용 방식은 계획서가 "홈·알림 우선순위 반영"까지 적었지만, 우선순위 규칙은 아직 정해지지 않았다(어떤 이벤트를 얼마나 올릴지).
저장소부터 둔다.

## Decision

1. **V86**: `paper_accounts.initial_capital NUMERIC(18,4) NOT NULL DEFAULT 10000000` + CHECK `IN (10000000, 30000000, 100000000)`.
   서버 화이트리스트는 `paper.domain.PaperInitialCapital`(값이 같아야 한다). 엔티티 필드는 `updatable = false`.
2. **`POST /api/paper/account { initialCapital }`** — 계좌를 **처음 만들 때만** 시작 자금을 정한다.
   - 화이트리스트 밖(소수·음수·문자 포함)은 400.
   - `INSERT … ON CONFLICT (user_id) DO NOTHING` — 첫 주문의 지연 생성과 겹쳐도 먼저 들어간 행이 이긴다.
   - 이미 계좌가 있으면 잔고를 건드리지 않는다. 같은 값이면 200(`created=false`, 멱등), 다른 값이면 409.
   - 처음 만들면 201. `GET /api/paper/account`는 시작 자금·현금(없으면 204).
3. **대사**: `LedgerReconciliationService`·`ReconciliationQueryService`의 초기 지급 항을 `paper_accounts.initial_capital`로
   (계좌 행이 없으면 1,000만 — 이전과 같은 해석).
4. **초기화**: 기존 규칙(미체결 주문이 있으면 거부, 조건부 주문 취소, 원장 기록, 하루 3회)을 그대로 두고, 되돌리는 값만 계좌의
   `initial_capital`로 바꾼다. 기존 계좌는 모두 1,000만이라 동작이 같다. 시작 자금을 바꾸는 경로는 없다.
5. **V86 `user_preferences`**(`user_id` PK, `interest_sectors VARCHAR(32)[]`, `usage_style`) + CHECK(최대 9개, enum 이름의 부분집합,
   스타일 3종). `PUT/GET /api/users/me/preferences` — 대상 사용자는 토큰 주체로만 정한다. 본문 목록은 20개까지, 모르는 값은 400,
   중복은 순서를 지켜 제거한다. **저장·조회만** 한다.

## Reasons

- 대사의 "초기 지급"을 데이터로 만들면 원장에 가짜 입금을 쓰지 않고도 불변식이 계좌마다 맞는다.
- 처음 생성에만 정하게 하면 "시작 자금 바꾸기"가 잔고를 임의로 늘리는 우회로(초기화 레이트 리밋·미체결 확인을 건너뜀)가 되지 않는다.
- 화이트리스트를 서버와 DB 둘 다에 두면 한쪽이 빠져도 대사가 깨지는 값이 들어가지 않는다.

## Consequences

- 시작 자금을 바꾸고 싶은 사용자는 방법이 없다(초기화도 같은 값으로 되돌린다). 요청이 생기면 별도 경로(미체결·조건부 확인, 원장 기록)를 설계한다.
- `ledger_snapshots`에는 초기 지급이 없어 `ReconciliationQueryService`가 계좌 행을 한 번 더 읽는다. 시작 자금은 바뀌지 않으므로 과거 스냅샷과 어긋나지 않는다.
- 화이트리스트는 세 곳(Kotlin 상수, V86 CHECK, 웹 `PAPER_INITIAL_CAPITALS`)에 있다. 값을 추가하려면 새 마이그레이션으로 CHECK를 바꾼다.
- 관심 분야는 아직 아무 기능에도 영향을 주지 않는다 — 화면 문구도 "준비 중"으로 둔다.

## Revisit When

- 홈·알림 우선순위에 관심 분야를 반영할 때(규칙 정의, 섹터 enum과 `stocks.sector` 매핑).
- 사용자가 시작 자금 변경·추가 입금을 요구할 때.
- 모의 계좌를 사용자당 여러 개로 늘릴 때(`user_id` UNIQUE 전제가 바뀐다).
