# monticker — 입력값·논리분기 검증 강화 작업계획

> Read this when: 실주문/리밸런싱/조건부주문/옵티마이저 경로에 **잘못된 입력이나 놓친 분기**가
> 없는지 점검하거나, 그 개선을 착수·우선순위화할 때. 보안(인증·토큰·암호화·주입) 관점은
> [security-review.md](security-review.md), 장애 대응은 [resilience-plan.md](resilience-plan.md),
> 출시 게이트는 [launch-plan.md](launch-plan.md).

작성일: 2026-09-21 · 기준 커밋: `bbdb567` · 상태: **✅ 전 항목 반영 완료 (PR #80, 2026-09-21)**

이 문서는 "검증이 충분히 작성돼 있는가"를 코드 레벨에서 점검한 결과다. [security-review.md](security-review.md)의
C3(실주문 API 입력검증 부재)·H2(41개 컨트롤러 중 39개 Bean Validation 없음)와 **겹치는 부분은
재기술하지 않고 참조**하며, 그 문서가 다루지 않은 **논리 분기·수량/가격 검증·옵티마이저/리밸런싱
로직**의 결함을 추가로 정리한다.

> **진행 상태 (2026-09-21 업데이트)** — 아래 §2~5의 발견 항목은 **PR #80에서 P0/P1/P2 전부 코드에
> 반영**됐다([launch-plan.md](launch-plan.md) Phase 2.1이 종결을 추적). 본 세션에서 병합된 코드를 직접
> 재확인한 항목: **V-C1**(`BrokerageController.submitOrder`에 `@Valid` + 경계 `valueOf`, `BrokerageService`가
> 미등록 종목을 리스크 게이트 건너뛰기 대신 거부), **V-H1**(matching 수량 클램프·`isValid`·`MAX_ORDER_QUANTITY`),
> **V-H2**(`ConditionalOrderService` `require(quantity>0)`), **V-H3**(`RiskRuleQueryService`의 `qty<=0` 가드 +
> `estimatedPrice<=0` 보수적 처리), **V-H4**(`riskCheckMutation` `res.ok`), **V-M1**(`isLimitPriceValid`),
> **V-M4**(`minimizeVariance`가 `targetReturn`을 실제 사용), **V-M5**(`distinct()` + `MAX_STOCK_IDS=20`),
> **V-M6**(리밸런싱 미저장 편집 시 실행 차단), **V-L2**(90일 만료 스케줄러). 해당 소스 다수가 주석에
> 본 문서의 finding ID(V-C1/V-H3/V-L2 등)를 인용한다.
>
> **의도적으로 유지(수정 안 함)**: V-L1 계열의 "HTTP 200 + `error` 필드" 반환 계약(옵티마이저·
> `RegimeDetectorService`) — 호출자가 `error`를 확인하는 반환값 기반 에러 규약으로 남긴다.
> **사람이 처리할 잔여**: launch-plan Phase 2.1의 인프라 항목(monticker-tls Secret, Redis/ES 운영 인증
> 토폴로지 확인, JWT iss/aud 도입에 따른 1회성 세션 무효화, CSP unsafe-inline 트레이드오프 등).
>
> 따라서 §6의 "작업 계획"은 **역사적 기록**이며, 신규 착수 대상은 없다.

---

## 0. 판정 요약

**결론: 검증 인프라(전역 예외 매핑, 리밸런싱 목표 저장, 조건부주문 컨트롤러 경계)는 견고한 편이지만,
"돈이 실제로 나가는" 세 지점에서 검증이 분기보다 늦거나 아예 비어 있다.**

| 주제 | 상태 | 대표 근거 |
|------|------|-----------|
| **실주문 경로의 검증 순서** | ❌ 브로커 호출이 enum/수량 검증보다 **먼저** 실행됨 → 고아 실거래 | `BrokerageService.kt:118` vs `:125-126` |
| **수량(>0, 정수) 검증** | ❌ 프론트·백 전 계층에서 산발적으로 누락 | `matching/page.tsx:185`, `ConditionalOrderService.kt:39-76` |
| **리스크 게이트 우회** | ❌ 수량≤0 / 추정가=0 이면 집중도 규칙이 무의미하게 통과 | `RiskRuleQueryService.kt:65-82`, `BrokerageService.kt:108` |
| **옵티마이저/리밸런싱 로직** | ⚠️ 데드 파라미터·상한 없음·중복 미제거·오버플로 | `PortfolioOptimizerQueryService.kt:86`, `RebalanceTargetService.kt:38` |
| **프론트 폼 피드백** | ⚠️ 조용한 강제변환(NaN→null, 에러본문→성공)·미저장 편집 무경고 | `matching/page.tsx:101-120` |
| 전역 예외 분류 | ⚠️ `IllegalStateException`을 **한국어 키워드 문자열 매칭**으로 4xx/500 구분(취약) | `GlobalExceptionHandler.kt:96-108` |

가장 시급한 것은 **실주문 경로(V-C1)** 다. 나머지는 GA 전 순차 처리 가능하다. 각 항목의 잠금
여부(다른 세션이 편집 중인 9개 브로커리지 파일)는 §2~5에 표기했다 — 잠긴 파일은 이 계획에서
**수정하지 않고 문서화만** 하고, §6에서 담당 세션/후속 작업으로 넘긴다.

---

## 1. 범위 & 방법론

- **대상**: 프론트(`apps/web`)의 주문/리밸런싱/조건부주문/온보딩 폼 + 백엔드(`backend/api`, `backend/worker`)의
  브로커리지·리밸런싱·조건부주문·리스크·옵티마이저·AI 제안 경로.
- **방법**: 프론트/백 두 축으로 읽기 전용 리뷰를 병렬 수행하고, **Critical·High와 내가 작성한 코드 관련
  발견은 직접 `Read`로 파일:라인을 재검증**했다. 재검증에서 백엔드 리뷰의 한 항목(리밸런싱 int
  오버플로)이 과장돼 있음을 확인해 하향 조정했다(§5 V-L5) — `RebalanceExecutionService.kt:143`의
  `if (quantity <= 0) return null` 가드가 음수 오버플로를 이미 걸러낸다.
- **판정 기준**: 파일:라인 근거 + 구체적 실패 시나리오가 있는 것만 발견으로 인정. "이럴 수도 있다"는
  추측은 제외했다.

---

## 2. Critical

### V-C1 — 실브로커 주문이 enum 검증보다 먼저 나가고, 검증 실패 시 로컬 기록 없는 고아 거래가 남는다
**근거**: `BrokerageService.kt:118` `val result = client.submitOrder(credentials, request)` — raw `request.side`/
`request.orderType` 문자열로 **실제 브로커 호출**. 그 뒤 `:125-126`에서야 `OrderSide.valueOf(request.side)`·
`OrderType.valueOf(request.orderType)`를 호출한다. `BrokerageController.kt:148-149`는 `uppercase()`만 하고
enum 검증을 하지 않는다(같은 프로젝트의 `ConditionalOrderController`는 컨트롤러 경계에서 검증함).

**시나리오**: `POST /api/brokerage/orders {"side":"hold","orderType":"stop","quantity":10,"symbol":"005930"}`
→ 리스크 규칙의 `if (side == "BUY")` 분기(C3 패턴)가 SELL로 처리 → `KisBrokerageClient.kt:122`가
**실제 SELL**을, `:129`가 `limitPrice ?: "0"` 로 **가격 0 지정가**를 브로커에 전송 → 이후 `valueOf("HOLD")`가
던져 클라이언트는 400을 받지만 **실거래는 이미 브로커에 도달**했고 `orderRepo.save`(`:134`)는 실행되지
않아 `BrokerageOrder` 행·정산·`risk_check_logs` 연결이 전혀 없다.

**관계**: security-review C3("비-BUY가 조용히 SELL로")를 **"검증보다 먼저 실거래가 나간다"**는 각도로 심화.
**조치**: `BrokerageOrderRequest`에 Bean Validation(`@Pattern`/`@Positive`) + 컨트롤러 경계 enum 검증을 두어
`submitOrder` **진입 즉시** 거부. 🔒 **잠김**(`BrokerageService.kt`·`BrokerageController.kt`) → §6에서 담당 세션 협업.

---

## 3. High

### V-H1 — 모의주문(matching) 수량 무검증: 0·음수 주문 제출 가능
**근거**: `matching/page.tsx:185` `onChange={e => setQuantity(+e.target.value)}` — 클램프 없음(`min={1}`은 HTML
힌트일 뿐). 빈 칸 → `+"" = 0`, `-5` 입력 → `-5`. 제출 버튼(`:225`)은 `submitMutation.isPending`만으로 게이트
되고 `isValid` 검사가 전무하다. 앱의 다른 주문 폼(`conditional-orders`, `orders`)은 `Math.max(1, Number(...))`를
쓰는데 이 폼만 예외.
**조치**: `setQuantity(Math.max(1, Math.floor(Number(...)||1)))` + `isValid`(정수·>0) 게이트. ✅ 수정 가능.

### V-H2 — 조건부주문 quantity 미검증 → 미래 시점에 무인 발주
**근거**: `ConditionalOrderService.kt:39-53`(`create`)·`:57-76`(`createOco`)가 `validateLeg()`(triggerPrice>0,
LIMIT가격)만 검증하고 `quantity`(별도 파라미터)는 검증하지 않는다. `quantity=0`/음수로 등록 성공 →
`ConditionalOrderEvaluator.fire()`가 **미래 틱에 비동기로** 실거래를 낸다(등록 시점 동기 검증 없음).
**조치**: `create`/`createOco` 진입부에 `require(quantity > 0)` + 정수 보장. ✅ 수정 가능.

### V-H3 — 리스크 게이트가 수량≤0 / 추정가=0 으로 무력화
**근거**: `RiskRuleQueryService.kt:65-82`(집중도 규칙) `newHoldingValue = currentValue + estimatedPrice * qty`
— 음수 `qty` 미거부. `BrokerageService.kt:108` `estimatedPrice = limitPrice ?: currentPrice ?: BigDecimal.ZERO`.
둘 중 하나면 `newHoldingValue`≈0/음수 → `concentrationPct`가 실노출과 무관하게 통과 → 같은 잘못된 수량이
그대로 브로커로.
**조치**: 상류에서 `qty>0` 강제(V-C1/H2와 함께). 추정가를 못 구하면 통과가 아니라 **보수적 거부**.
🔒 부분 잠김(`BrokerageService.kt`) / ✅ `RiskRuleQueryService.kt`는 수정 가능.
**후속(2026-09-21, P0 회귀)**: 보수적 거부(5c53b2b)가 페이퍼 **시장가 매수 전부**를 막았다 — MARKET 주문은 지정가가
없어 `RiskCheckedAspect`(가격 파라미터 없는 `MatchingService.submitMarket`)와 `MatchingController`(`limitPrice ?: ZERO`)가
게이트에 ZERO를 넘기기 때문. `RiskCheckerService.check`가 `estimatedPrice<=0`이면 `RiskRuleQueryService.currentPrice`
(candles_1m 최근가 — 사가의 예약금 기준과 같은 조회)로 채우고, 그것도 없을 때만 불명으로 남겨 거부한다. "모르는 가격으로 승인"
금지는 그대로다. 실거래 `checkBrokerageOrder`는 호출자가 가격을 명시하는 계약이라 폴백 대상이 아니다.

### V-H4 — `riskCheckMutation`이 `res.ok` 미확인 → 에러 본문을 리스크 결과로 렌더
**근거**: `matching/page.tsx:101-110` — `submitMutation`(`:122`)과 달리 `if (!res.ok) throw`가 없어 4xx/5xx
본문을 `RiskCheckResult`로 취급. `RiskPreview`(`:201`)의 `result.checks.map(...)`(`:72`)이 `checks` 부재 시
크래시하거나 오해 소지의 "차단" 사유를 표시.
**조치**: `submitMutation`과 동일하게 `if (!res.ok) throw`. ✅ 수정 가능.

---

## 4. Medium

### V-M1 — LIMIT 주문에서 빈/비숫자 가격이 `limitPrice: null`로 조용히 전송
`matching/page.tsx:106-107,119-120`: `orderType==="LIMIT" && limitPrice ? parseFloat(limitPrice) : null`. LIMIT인데
가격 미입력 → `null` 전송(주문유형/가격 불일치, 사전 차단 없음). `"abc"` → `NaN` → `JSON.stringify`가 `null`로.
**조치**: LIMIT일 때 가격 필수·숫자·>0 검증 후 제출. (`conditional-orders`/`orders` 유사 확인.)

### V-M2 — 조건부주문 가격 방향 정합성 미검증
`conditional-orders/page.tsx:70-71,284-285`: `stopLossPrice < 현재가 < takeProfitPrice`(OCO) 및 STOP_LOSS/
TAKE_PROFIT/PRICE_ABOVE/PRICE_BELOW의 방향을 강제하지 않는다. 반전된 가격은 등록 즉시 "이미 참"이라
다음 틱에 **즉시 실거래**로 발동될 수 있다.
**조치**: 트리거 유형별 방향 검증 + 즉시발동 경고.

### V-M3 — `thresholdPct` 상한 없음 → 저장은 성공, 실행은 영구 무력
`RebalanceTargetService.kt:38`은 `thresholdPct > 0`만 검증. `10000` 저장 성공(200) → `RebalanceExecutionService.kt:127`
에서 `thresholdFraction=100` → `:135` `diffPct.abs() < thresholdFraction` 항상 참 → `execute()`가 항상 "대상 없음"(409).
저장은 "성공"했는데 쓸 수 없는 설정.
**조치**: 상한(예: `≤ 100`) 검증 또는 UI에서 합리적 범위 강제.

### V-M4 — 옵티마이저 `targetReturn`이 데드 파라미터 → frontier가 무의미
`PortfolioOptimizerQueryService.kt:86-95` `minimizeVariance(cov, mu, targetReturn, iterations)` — `targetReturn`을
본문에서 전혀 참조하지 않고 항상 무제약 전역 최소분산해를 반환. 따라서 `?targetReturn=X`(`AnalyticsController.kt:24-28`)는
무시되고 frontier 10점이 **동일 가중치 벡터**로 수렴.
**조치**: 제약 최적화(목표수익률별)를 실제 구현하거나, 파라미터를 제거하고 프론트에서 "효율적 프론티어" 표기를 정정.

### V-M5 — `stockIds` 상한/중복제거 없음 → 캐시 우회 + CPU/DB 증폭 + 잘못된 결과
`PortfolioOptimizerQueryService.kt:17-20,63-64`는 `size<2`만 거부. 상한·`distinct()` 없음. 중복 id는
`stockIds.indices.associate{...}`(`:42,:82`)에서 마지막 것만 남아 조용히 잘못된 결과. 무제한 리스트는 id당
전구간 JDBC 스캔 + O(n²) 공분산 + `500×10` 경사하강을 유발하고, `@Cacheable` 키가 배열 리터럴(`PortfolioOptimizerService.kt:38`)
이라 순열/중복으로 캐시 우회.
**조치**: `distinct()` + 상한(예: `≤ 20`).

### V-M6 — 리밸런싱 preview/execute가 항상 **저장된** target만 사용(미저장 편집 무경고)
`rebalance/page.tsx:324-371`: 실행 섹션이 편집 중이거나 옵티마이저로 막 채운 `rows`가 아니라 영속 `target`을
대상으로 한다. "목표 비중 저장"을 누르지 않고 미리보기/실행하면 조용히 **stale 데이터**로 동작. (내가 이번
세션에 추가한 옵티마이저 채우기 흐름이 이 괴리를 더 잘 드러냄.)
**조치**: dirty 상태 표시 + 저장 유도(미저장 시 실행 비활성 또는 경고).

### V-M7 — 실주문 폼 수량이 잔고/보유 대비 무검증(클라), 잔고 로딩/에러 분기 없음
`orders/page.tsx:66-77`은 `maxQty`(가용현금)와 `holding.quantity`(매도)를 표시만 하고 `isValid`에 넣지 않음.
`:42,67,71` — `useBrokerageBalance` 로딩/에러 분기가 없어 `balance` `undefined`면 "가용 현금 ₩0"·`maxQty=0`으로
**정상 잔고 사용자를 조용히 차단**(빈 계좌와 구분 불가).
**조치**: `isValid`에 상한 반영 + 로딩/에러 상태 분기.

---

## 5. Low

- **V-L1** 옵티마이저 실패가 HTTP 200 + `error` 필드(4xx 아님). `PortfolioOptimizerQueryService.kt:19,24-26,68`.
  호출자가 `error` 미확인 시 `weights={}`를 성공으로 취급. → 4xx로 던지거나 호출자에 `error` 확인 강제.
- **V-L2** `ConditionalOrder.expiresAt`/`EXPIRED` 미사용 — 조건부주문이 영구 `ACTIVE`. `ConditionalOrder.kt:22,77`,
  `ConditionalOrderService.kt:39-76`에서 미설정, 만료 스케줄러 없음(리포 전수 grep 확인). 잊힌 조건이 **낡은
  트리거가로 실거래**를 낼 수 있다. → 만료 잡 도입 또는 필드/상태 제거.
- **V-L3** `OrderProposal.approve()`가 `HOLD` 승인 미차단. `OrderProposal.kt:6`. `HOLD`가 free-text `side`로 흘러
  V-C1/H3의 악성 입력이 됨. → `approve()`에서 `side==HOLD` 거부.
- **V-L4** (내 코드) 옵티마이저 `result.weights[id] ?? 0` — 옵티마이저가 특정 종목 비중을 생략하면 해당 행이
  조용히 0%가 되고, `isSaveValid`(모든 비중>0)로 **원인 표시 없이 저장 차단**. `rebalance/page.tsx:118`. →
  생략 종목을 경고하거나 목록에서 제거.
- **V-L5** 리밸런싱 int 오버플로 — `RebalanceExecutionService.kt:141` `.toInt()`가 32비트 wrap(예외 없음). 단
  `:143` `if (quantity <= 0) return null` 가드가 음수 wrap을 걸러내므로 **양수 wrap(희박)만 잔존**. → `Long`
  사용 또는 상한 체크.
- **V-L6** 종목검색 디바운스에 `AbortController`/요청 id 가드 없음 → stale 응답이 최신 결과 덮어씀.
  `rebalance:70-77`, `conditional-orders:51-58`, `orders:45-52`.
- **V-L7** 전 주문 폼에 수량 **상한** 없음 → `1e9` 같은 값이 클라 검사를 통과.
- **V-L8** `matching/page.tsx:264-269`·유사 취소 경로의 `cancelMutation`이 `res.ok` 미확인 → 실패한 취소가
  성공처럼 처리.

---

## 6. 우선순위별 작업 계획

### P0 — 실브로커 주문을 여는 것 자체보다 먼저 닫아야 (실계좌·실자금)
| ID | 작업 | 잠금 | 담당 |
|----|------|------|------|
| V-C1 | `BrokerageOrderRequest` Bean Validation + 컨트롤러 경계 enum/수량 검증(브로커 호출 **이전**) | 🔒 | 브로커리지 파일 담당 세션과 협업 — 이 계획에서 코드 미변경 |
| V-H2 | `ConditionalOrderService.create/createOco`에 `require(quantity>0)` + 정수 | ✅ | 착수 가능 |
| V-H3 | `RiskRuleQueryService` 음수 수량 거부 + 추정가 0 보수적 처리 | ✅/🔒 | 리스크 규칙은 착수 가능, `BrokerageService` 부분은 V-C1과 함께 |
| V-H1 | matching 수량 클램프 + `isValid` 게이트 | ✅ | 착수 가능 |

> **주의**: V-C1·V-H3의 `BrokerageService.kt`/`BrokerageController.kt`는 현재 다른 세션이 편집 중인 9개
> 잠금 파일에 속한다. 본 세션에서는 **문서화만** 하고 수정하지 않는다.

### P1 — GA 전
V-H4, V-M1, V-M2, V-M3, V-M4, V-M7, V-L3, 그리고 **전역 예외 분류 개선**(아래 §주의).

### P2 — 여유 있을 때
V-M5, V-M6, V-L1, V-L2, V-L4, V-L5, V-L6, V-L7, V-L8.

---

## 7. 잘 되어 있는 부분 (재확인)

- **전역 예외 매핑 일관성**: `GlobalExceptionHandler`가 `IllegalArgumentException`→400, `NoSuchElementException`→404,
  `MethodArgumentNotValidException`→400, `RiskLimitException`→422로 매핑. 덕분에 `valueOf(...uppercase())`·`require(...)`
  사이트가 500이 아니라 400으로 안전하게 떨어진다(백엔드 리뷰가 이를 명시적 non-issue로 확인).
- **리밸런싱 목표 저장 검증**: `RebalanceTargetService.save`가 비어있음/합>100%/개별≤0/미존재 종목/threshold≤0를
  모두 `require`로 차단(`:31-38`). — 프론트의 반올림 초과분 보정(이번 세션 추가)이 이 서버 검증과 정합.
- **조건부주문 컨트롤러 경계 검증**: `ConditionalOrderController`가 side/triggerType/orderType를 경계에서 `valueOf`→400.
  (실주문 컨트롤러가 이렇게 안 하는 것이 V-C1의 근본 원인.)
- **AI 주문 제안의 구조적 안전장치**: 승인이 제안 상태만 바꾸고 주문을 내지 못함(ADR-036).

### ⚠️ 별도 주의 — 취약한 에러 분류 분기
`GlobalExceptionHandler.kt:96-108`은 `IllegalStateException`을 **한국어 키워드 문자열 매칭**(`"잔고"`, `"불가"`,
`"없음"`, `"계좌가 없습니다"` 등)으로 409/500을 가른다. 매직 키워드가 없는 신규 비즈니스 규칙 메시지는
**500으로 새고 에러 로그로 남는다** — `ReconnectRequiredException.kt`가 이 휴리스틱이 실제로 오작동했던
라이브 사고를 주석으로 기록하고 있다. 검증 자체가 아니라 **에러 분류 분기의 취약성**이므로, 명시적
비즈니스 예외 타입(또는 `sealed` 도메인 예외) 도입을 P1에서 권장.

---

## 8. 기존 문서와의 관계

- [security-review.md](security-review.md): 본 문서의 V-C1/V-H3는 그 문서 **C3**(실주문 입력검증 부재)를 심화하고,
  V-H1/H2/M1은 **H2**(Bean Validation 부재)의 구체 사례다. 보안 관점(위조·탈취·주입)은 그 문서가, **정상 사용자의
  잘못된 입력·놓친 분기**는 본 문서가 담당한다.
- ADR-025(리스크 게이트): V-H3가 게이트 우회 경로를 지적.
- ADR-034(리밸런싱 실행): V-M3/M6/L5가 해당.
- ADR-036(AI 제안): V-L3(HOLD 승인)이 해당.
