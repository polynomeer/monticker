# ADR-092: 주문 전 리스크 체크 미리보기 — 감사 기록 없는 별도 엔드포인트

## Status
Accepted

[ADR-025](025-real-brokerage-order-safety-gate.md)(리스크 게이트)·[ADR-069](069-risk-limit-changes-cooling-off.md)(유효 한도)를
그대로 쓴다. `POST /api/risk/check`(사전 점검, `dry_run` 감사 행)도 그대로 둔다.

## Context

종목 화면 주문 패널의 "주문 전 리스크 체크"는 버튼(`점검하기`)을 눌러야 `POST /api/risk/check`를 불렀다. 이 엔드포인트는
판정마다 `risk_check_logs`에 `dry_run = true` 행을 남긴다(차단 기록·이번 달 집계에서는 빠지지만 감사 테이블에는 쌓인다).
그래서 입력할 때마다 부를 수 없었다 — 타이핑 중간값(수량 1, 12, 120 …)이 전부 "판정"으로 기록된다.
시안은 입력하는 대로 결과가 바뀌는 패널이다([design-rollout-plan](../design-rollout-plan.md) §2 종목 트레이딩).

고려한 대안:

- **A. 기존 `/api/risk/check`를 디바운스로 자동 호출** — 감사 행이 입력마다 쌓이고, 30회/분 한도에 타이핑이 걸린다.
- **B. `/api/risk/check`에 `audit=false` 플래그** — 같은 경로가 요청 값에 따라 기록하거나 안 한다. 감사를 끌 수 있는 스위치가
  클라이언트 손에 있게 되고, 한도도 감사되는 호출과 나눠 쓴다.
- **C. 클라이언트에서 계산** — 규칙(VaR·집중도·섹터·일간 손실·시간당 주문)이 서버 데이터(보유·체결·대기 매수)에 걸려 있어
  같은 판정을 낼 수 없다.
- **D. 감사·메트릭 없는 별도 미리보기 엔드포인트** — 판정식은 공유, 부수 효과만 뺀다.

## Decision

**D.** `POST /api/risk/preview` (`risk.api.RiskPreviewController`).

```
RiskCheckerService.preview(userId, stockId, side, qty, estimatedPrice)   @Transactional(readOnly = true)
  requireSide → ensureStockExists → limitService.effective → rules.evaluate → judge(checks)
  (finalize와 달리 auditLogger.record·risk_check_total 증가 없음)
```

- **같은 판정**: `dryRun`과 같은 규칙·같은 유효 한도(완화 대기 포함)·같은 "체크 꺼짐이면 수량 검증만" 분기. 판정식
  (`judge`)을 `finalize`에서 뽑아 둘이 공유한다. 단위 테스트가 같은 입력에서 `preview == dryRun`을 확인한다.
- **부수 효과 없음**: 감사 행 없음, `risk_check_total` 없음. 읽기 전용 트랜잭션이라 쓰기 경로가 끼어들면 DB가 거부한다.
  주문·예약·현금 홀드·시간당 주문 수 어느 것도 바뀌지 않는다. 통합 테스트가 실제 Postgres에서 `risk_check_logs` 0행을 확인하고,
  대조군으로 같은 입력의 `dryRun`이 1행을 남기는지도 본다.
- **같은 입력 검증 + 정수 수량**: side는 `BUY`/`SELL`만(대소문자 정규화 없음), 수량은 0 초과 정수(1,000,000 이하), 가격은 0 이상
  (0·생략 = 시장가 → 서버 최근가). 수량을 `BigDecimal`로 받아 정수 여부를 직접 본다 — Jackson 기본 설정은 `1.5`를 `Int`에
  `1`로 잘라 넣는다. 없는 종목은 404.
- **호출 제한**: 사용자당 60회/분, 키 `risk.preview`(감사되는 `risk.dryrun`·주문 `matching.order`와 한도를 나눠 쓰지 않는다).
  웹은 입력이 멈추고 400ms 뒤에 부르고, 첫 값도 400ms를 기다린다. 429면 재시도하지 않고 안내만 한다.
- **주문으로 쓸 수 없음**: 응답은 판정뿐이다. 토큰·예약 id·유효 시각 같은 "다음 단계로 가져갈 값"이 없다. 실제 주문(모의
  `@RiskChecked`, 실거래 `prepareOrder`)은 제출 시점에 게이트를 다시 돌고 그때 감사 행을 남긴다.
- **웹**: 종목 화면 주문 패널의 `점검하기` 버튼을 없애고 미리보기로 바꿨다. 직전 입력의 결과는 새 결과가 올 때까지 흐리게
  보이고, "미리보기 — 주문할 때 서버가 다시 판정합니다"를 단다. `/api/risk/check`는 다른 화면(`components/matching/OrderForm`)이
  계속 쓴다 — 이 폼에서 감사된 사전 점검이 필요할 이유가 없다(제출이 감사된다).

## Reasons

- 감사 테이블의 의미가 유지된다: `risk_check_logs`는 "주문(또는 사용자가 명시적으로 요청한 사전 점검)에 대한 판정"만 담는다.
- 판정식이 한 곳(`judge` + `RiskRuleQueryService`)이라 미리보기와 실제 게이트가 갈라지지 않는다.
- 감사를 끄는 스위치가 요청 값에 없다(B와 달리) — 엔드포인트가 다르면 권한·한도·로그를 따로 볼 수 있다.

## Consequences

- 미리보기 판정은 기록이 없다. "사용자가 한도 초과를 보고도 주문했다"를 재구성하려면 주문 시점 감사 행만 있다.
- 판정 비용(보유·VaR·섹터 조회)은 주문과 같다. 60회/분 × 사용자 수만큼 읽기 부하가 는다. 디바운스와 사용자당 한도로 묶는다.
- 미리보기와 제출 사이에 상태가 바뀌면(다른 체결·한도 강화) 결과가 다를 수 있다. 화면 문구로 알린다.
- 실거래 미리보기는 이 ADR 범위가 아니다. 실거래 게이트는 증권사 잔고 스냅샷(증권사 호출)이 필요해 입력마다 부를 수 없다.

## Revisit When

- 실거래 주문 화면에 같은 미리보기가 필요해질 때 — 잔고 스냅샷 캐시(ADR-070의 실계좌 보류 사유와 같다)가 먼저다.
- 미리보기 판정도 감사 대상이어야 한다는 규제·법무 판단이 나올 때 — 별도 테이블(보존 기간 짧게)로 분리한다.
- 판정 비용이 문제될 때 — 사용자별 보유·VaR 입력을 짧게 캐시한다(수량·가격만 바뀌는 타이핑에는 같은 입력이다).
