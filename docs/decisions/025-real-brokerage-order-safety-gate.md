# ADR-025: 실거래(BYOK) 주문 안전장치 + KIS 클라이언트 버그 수정

## Status
Accepted

## Context

[docs/product.md](../product.md)는 실제 주문 체결을 "BYOK 모델 — 기존 `BrokerageClient` 인터페이스에 `TossBrokerageClient`를 추가하는 형태"로 설명한다. 실제 코드를 확인한 결과, 이미 구현된 부분(`BrokerageClient`/`KisBrokerageClient`/`MockBrokerageClient`, 계좌 연동 API, circuit breaker, rate limit)과 실제로는 비어있거나 깨진 부분이 섞여 있었다:

1. **실거래 주문이 페이퍼 트레이딩용으로 이미 만들어진 안전장치를 전부 우회한다.** `POST /api/brokerage/orders` → `BrokerageService.submitOrder()` → `BrokerageClient.submitOrder()` 직행 — 매칭엔진(CLOB), 5개 리스크 룰(`RiskCheckerService`), Saga/보상 트랜잭션 전부 `paper`/`matching` 모듈에만 걸려 있고 `brokerage` 모듈은 참조조차 하지 않는다.
2. **`KisBrokerageClient`는 진짜 HTTP 클라이언트지만 실거래하면 반드시 실패한다** — 공식 참조 구현체([Soju06/python-kis](https://github.com/Soju06/python-kis))로 확인한 결과:
   - 계좌번호(CANO/ACNT_PRDT_CD)가 모든 요청에서 빈 문자열로 하드코딩됨.
   - **`appkey`/`appsecret` 헤더가 토큰 발급 이후의 모든 인증 호출에서 통째로 빠져 있다** — KIS는 `authorization`/`tr_id`뿐 아니라 매 요청마다 `appkey`/`appsecret` 헤더를 요구하는데, 이 앱은 `connect()` 시점에 토큰 발급에만 쓰고 버린다(`BrokerageAccount`에 저장 안 함).
   - `getOrderStatus()`가 잘못된 엔드포인트(`inquire-psbl-rvsecncl`, "정정취소가능주문조회")를 쓰고, 그 목록에 없으면(=이미 전량체결되어 목록에서 빠진 경우 포함) "제출됨"으로 잘못 판단한다.
   - `getSettlements()`는 잔고조회용 TR_ID(`TTTC8434R`)를 엉뚱한 엔드포인트에 붙여 쓰고 있다. 다만 `BrokerageService`가 이 메서드를 아예 호출하지 않아(정산은 로컬 `brokerage_settlements`로 직접 처리) 현재는 죽은 코드다.
   - `resolveStockId(symbol)`이 항상 `null`을 반환하는 스텁이다.

이번 ADR은 **실거래 주문에 페이퍼 트레이딩과 동등한 사전 리스크 게이트를 적용**하고, **KIS 클라이언트를 실제로 동작 가능한 상태로 고치는** 두 가지를 다룬다. Toss Securities 연동, 브로커별 라우팅(팩토리/레지스트리), 계좌 연동 프론트엔드, 자격증명 재발급/만료 UX 정책은 범위 밖 — 각각 후속 ADR로 넘긴다.

## Decision

### 1. 리스크 룰 엔진을 계좌 유형에 무관하게 만든다

`RiskRuleQueryService.evaluate()`는 현재 `paper_accounts`/`paper_trades`/`orders` 테이블을 직접 쿼리한다. 이 5개 룰(일간손실/집중도/VaR/보유종목수/시간당주문)의 **판정 로직 자체는 계좌 유형과 무관**하므로, "포트폴리오 스냅샷을 어디서 가져오는가"와 "그 스냅샷으로 무엇을 판정하는가"를 분리한다.

```kotlin
data class PortfolioSnapshot(
    val cash: BigDecimal,
    val holdings: List<HoldingPosition>,   // stockId, qty
    val dailyPnl: BigDecimal,
    val recentOrderCount: Long,            // 최근 1시간
)
data class HoldingPosition(val stockId: Long, val qty: Int)
```

- `RiskRuleQueryService.evaluate(...)` (페이퍼, 기존 시그니처 그대로) — 내부에서 기존 jdbc 쿼리로 `PortfolioSnapshot`을 만든 뒤, 새로 추출한 `evaluateWithSnapshot(...)` private 함수를 호출하도록 리팩터링한다. **동작 변화 없음** — 룰 판정 로직을 한 곳으로 모으기 위한 내부 구조 변경일 뿐이다.
- `RiskCheckerService`에 `checkBrokerageOrder(userId, stockId, side, qty, estimatedPrice, snapshot)`를 추가한다 — `check()`와 동일하게 `risk_limits`를 조회하고 `risk_check_logs`에 기록하지만, 스냅샷은 호출자가 넘긴다.
- `risk_check_logs`에 `account_type VARCHAR(10) NOT NULL DEFAULT 'PAPER'` 컬럼을 추가해 페이퍼/실거래 로그를 구분한다.
- `risk_limits`는 그대로 `user_id` 단일 설정을 유지한다 — 사용자가 페이퍼/실거래용 리스크 허용치를 다르게 관리하고 싶어할 수도 있지만, 지금 스키마(`UNIQUE(user_id)`)를 쪼개는 건 이 ADR의 안전장치 목적에 비해 과한 범위 확장이라 판단했다. 필요해지면 별도 ADR.

`brokerage` 모듈은 `matching` 모듈의 `RiskCheckerService`/`PortfolioSnapshot`을 직접 참조한다 — 새 인터페이스를 만들지 않고 [ADR-019](019-spring-modulith-boundary-conventions.md)의 "여러 모듈이 서비스 계층을 합법적으로 참조" 패턴을 그대로 따른다.

### 2. `BrokerageService.submitOrder()`에 사전 리스크 게이트를 건다

```
symbol → stockId 해석 (fix: jdbc로 stocks 테이블 직접 조회, resolveStockId 스텁 제거)
→ BrokerageClient.getBalance()로 실시간 현금/보유 조회
→ brokerage_orders/brokerage_settlements로 dailyPnl/recentOrderCount 계산
→ PortfolioSnapshot 조립
→ RiskCheckerService.checkBrokerageOrder(...) — 승인 안 되면 RiskLimitException, 증권사에 보내지 않고 즉시 차단
→ (승인 시에만) BrokerageClient.submitOrder()
```

dailyPnl은 페이퍼와 동일하게 "당일 체결 기준 순현금흐름" 근사치로 계산한다 — `brokerage_settlements`는 T+2로 미래 날짜에 정산되므로 오늘자 리스크 판단에는 쓸 수 없고, `brokerage_orders`의 `filled_at >= 오늘`인 행에서 직접 계산한다.

### 3. KIS 클라이언트를 실제로 호출 가능하게 고친다

- `BrokerageAccount`에 `appKey`/`appSecret` 컬럼을 추가하고 기존 `accessToken`과 동일하게 `EncryptedStringConverter`(AES-256-GCM)로 암호화 저장한다. `connect()`가 토큰 발급 직후 버리지 않고 계정에 저장한다.
- `BrokerageClient` 인터페이스를 `token: BrokerageToken` 대신 `credentials: BrokerageCredentials(token, appKey, appSecret, accountNumber)`를 받도록 바꾼다 — CANO/ACNT_PRDT_CD와 appkey/appsecret 헤더를 모든 인증된 호출에 실을 수 있게 하기 위함이다. `issueToken()`만 기존 시그니처(appKey/appSecret 직접 전달) 유지.
- `KisBrokerageClient`: 모든 인증된 호출에 `appkey`/`appsecret` 헤더 추가, `accountNumber`를 CANO(앞 8자리)/ACNT_PRDT_CD(나머지, 없으면 "01")로 분리해 실제 값 전송.
- `getOrderStatus()`를 `/uapi/domestic-stock/v1/trading/inquire-daily-ccld`(TR_ID: 실전 `TTTC8001R` / 모의 `VTTC8001R`, `baseUrl`에 `vts` 포함 여부로 판별)로 교체하고, 상태는 `ord_sttsDvsnName`(실존하지 않는 필드였다) 대신 `rjct_qty`(거부수량)/`rmn_qty`(미체결수량)/`tot_ccld_qty`(체결수량)로 판정한다.
- `getSettlements()`도 같은 엔드포인트로 교체한다 — 다만 이 메서드는 여전히 아무도 호출하지 않는 죽은 코드이며, 이 응답 형태에서 수수료/세금 필드를 신뢰할 수 있게 확인하지 못했으므로 fee/tax는 0으로 채우고 그 사실을 주석에 남긴다.
- 이 클라이언트는 여전히 **실제 KIS 계정으로 한 번도 검증되지 않았다** — 이 ADR은 확인 가능한 스펙 불일치를 고치는 것이지, "실전 검증 완료"를 뜻하지 않는다. `docs/launch-plan.md` Phase 6의 "KIS 모의투자 계좌 E2E 1회 실행" 체크박스는 여전히 미완료로 남는다.

## Reasons

- 리스크 판정 로직을 스냅샷 기반으로 분리하면 페이퍼 트레이딩 코드 경로를 건드리지 않고도(동일 룰, 동일 계산식) 실거래에 같은 보호장치를 적용할 수 있다 — 두 배로 유지보수하거나 잘못된(페이퍼 계좌 기준) 판정을 실거래에 잘못 적용하는 위험을 피한다.
- appKey/appSecret을 암호화 저장하는 결정은 "고치는 척만 하는" 수정을 피하기 위한 필수 선행 조건이다 — 저장하지 않으면 CANO를 고쳐도 여전히 모든 인증 호출이 헤더 누락으로 거부된다. 재발급/만료 UX 정책(언제 재연동을 요구할지)은 별도로 남겨둔다.
- KIS 엔드포인트/TR_ID는 추측 대신 공식 유지보수 중인 오픈소스 참조 구현체로 교차 검증했다 — 그래도 실제 KIS 서버로 검증된 적은 없다는 한계를 그대로 기록해 향후 오해를 막는다.

## Consequences

- `BrokerageClient` 인터페이스 시그니처 변경(모든 메서드가 `BrokerageCredentials`를 받음)은 `MockBrokerageClient`도 함께 수정해야 하는 breaking change다 — 이번 ADR 범위에서 함께 처리한다.
- 실거래 주문에 리스크 체크가 추가되면서 `BrokerageService.submitOrder()`의 지연시간이 늘어난다(잔고 조회 + 로컬 집계 쿼리 추가) — 아직 실사용자가 없으므로 성능 영향은 무시할 수준.
- appKey/appSecret을 저장하면 DB가 탈취당했을 때의 피해 범위가 넓어진다(기존에는 access token만 있어도 24시간 후 자동 무효화됐지만, appSecret은 재발급 전까지 유효) — 이 trade-off는 사용자가 매일 재연동해야 하는 UX보다 낫다고 판단했지만, 키 로테이션/탈취 감지 정책은 아직 없다.
- Toss Securities 연동은 여전히 `TossBrokerageClient` 자체가 없다 — `BrokerageClient`가 앱 전체에 단일 Spring 빈으로만 주입되므로, Toss를 추가하려면 이번 ADR과 별개로 provider별 라우팅(팩토리/레지스트리) 설계가 먼저 필요하다.

## Revisit When

- Toss Securities 연동을 시작할 때 — provider별 라우팅 계층을 설계해야 하고, 그때 `BrokerageCredentials`가 KIS 전용 필드(CANO 등)를 그대로 노출하고 있는 게 맞는지도 재검토한다.
- 실제 KIS 모의투자 계좌로 첫 E2E 검증을 실행할 때 — `getOrderStatus()`의 상태 판정(특히 취소 여부)이 실제 응답과 맞는지 반드시 재확인한다. `rmn_qty`/`tot_ccld_qty`/`rjct_qty` 조합만으로는 "취소됨"을 확실히 구분하지 못해 보수적으로 추론했다.
- 계좌 연동 프론트엔드를 만들 때 — appKey/appSecret 재발급 정책(자동 갱신 vs 수동 재연동, 만료 임박 알림)을 결정해야 한다.
