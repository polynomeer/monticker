# ADR-026: Toss Securities(토스증권) Open API 연동 — provider 라우팅 계층 도입

## Status
Accepted

## Context

[ADR-025](025-real-brokerage-order-safety-gate.md)는 KIS 실거래 게이트만 다루고 Toss Securities 연동을 명시적으로 범위 밖에 두면서, "Toss 연동을 시작할 때 provider별 라우팅 계층을 설계해야 하고, `BrokerageCredentials`가 KIS 전용 필드를 그대로 노출하고 있는 게 맞는지 재검토한다"는 재검토 항목을 남겼다. 이번 ADR이 그 후속이다.

**실제 API 스펙 검증**: `docs/external-apis.md`는 Toss Securities Open API를 "planned"로 표시하고 `https://developers.tossinvest.com/docs`를 링크만 걸어뒀을 뿐, 실제 계약(엔드포인트, 인증 방식, 요청/응답 스키마)은 확인되지 않은 상태였다. 이번 작업에서 Toss가 공개한 정식 OpenAPI 3.0 스펙(`https://openapi.tossinvest.com/openapi-docs/latest/openapi.json`, 버전 1.2.14)을 직접 가져와 33개 엔드포인트 전체와 스키마를 확인했다 — ADR-025가 KIS를 `Soju06/python-kis` 참조 구현체로 교차검증한 것과 동일한 방식이다. 확인 결과, 문서의 다음 서술은 최신 상태와 달랐다:

- "WebSocket 지원이 추후 지원 예정" → 실제로는 `wss://openapi-ws.tossinvest.com/ws/v1` 및 AsyncAPI 스펙이 이미 존재한다(단, 이번 ADR은 주문 체결만 다루고 시세 스트리밍은 범위 밖 — `docs/external-apis.md`만 정정한다).
- KIS 클라이언트가 "서킷브레이커가 없다"는 서술도 사실이 아니었다 — `KisBrokerageClient`는 이미 `CircuitBreakerRegistry`의 `"kis"` 브레이커로 감싸여 있다(`common/resilience/CircuitBreakerConfiguration.kt`). Toss도 같은 패턴을 따른다.

**Toss Open API 실제 계약** (검증된 사실):
- 인증: OAuth2 Client Credentials Grant. `POST /oauth2/token` (form-urlencoded, `client_id`+`client_secret`) → `{access_token, token_type, expires_in}`. Refresh token 없음 — 만료 시 동일 엔드포인트로 재발급.
- 계좌 식별: 모든 계좌/주문 API는 `Authorization: Bearer {token}` 외에 `X-Tossinvest-Account: {accountSeq}` 헤더가 **필수**다. `accountSeq`는 사람이 아는 계좌번호(`accountNo`)와 다른 내부 정수 식별자로, `GET /api/v1/accounts` 응답에서만 얻을 수 있다 — **KIS의 CANO/ACNT_PRDT_CD처럼 계좌번호 문자열을 그대로 쪼개 쓸 수 없고, 반드시 별도 API 호출로 조회해야 한다.**
- 주문 생성: `POST /api/v1/orders` — `clientOrderId`(멱등성 키, 서버가 자동 생성하지 않음, 최대 36자, 10분 유효), `symbol`, `side`, `orderType`(LIMIT/MARKET), `quantity`(string) 또는 `orderAmount`, `price`(string, LIMIT 전용), `confirmHighValueOrder`(1억원 이상 주문 시 `true` 필수). 응답은 `{orderId, clientOrderId}`뿐 — 체결 여부는 별도 조회.
- 주문 상태: `GET /api/v1/orders/{orderId}` — `status` enum은 `PENDING/PENDING_CANCEL/PENDING_REPLACE/PARTIAL_FILLED/FILLED/CANCELED/REJECTED/CANCEL_REJECTED/REPLACE_REJECTED/REPLACED` (스펙 자체가 "클라이언트는 unknown code를 허용해야 한다"고 명시).
- 취소: `POST /api/v1/orders/{orderId}/cancel`.
- 잔고: `GET /api/v1/buying-power?currency=KRW` (매수가능금액) + `GET /api/v1/holdings` (보유종목, KR+US 혼합 응답을 통화별로 분리 제공).
- Rate limit: API 그룹별 초당 요청 한도(`ORDER`=10, `ACCOUNT`=1 등), 429에 `Retry-After` 헤더 제공.
- **모의투자(demo) 서버가 없다** — KIS와 달리 Toss Open API는 단일 실서버(`https://openapi.tossinvest.com`)뿐이다. 즉 Toss 쪽은 처음부터 실계좌로만 검증 가능하다.

**기존 아키텍처 제약**: `BrokerageService`는 지금 `BrokerageClient` 인터페이스를 **단일 Spring 빈**으로 주입받는다 — `app.brokerage.mock.enabled` 프로퍼티로 `MockBrokerageClient`/`KisBrokerageClient` 중 하나만 활성화되는 상호 배타적 `@ConditionalOnProperty` 구조다. Toss를 추가하면 계좌마다(사용자가 KIS를 쓸 수도, Toss를 쓸 수도 있으므로) 다른 클라이언트로 라우팅해야 하는데, 지금 구조로는 두 실제 구현체(KIS+Toss)가 동시에 존재할 방법이 없다.

또한 `BrokerageAccount.provider` 필드는 지금까지 **어디서도 명시적으로 설정된 적이 없다** — 엔티티 생성자 기본값(`BrokerageProvider.MOCK`)에 항상 의존해왔다(`grep`으로 확인, `connect()` 어디에도 `provider =` 대입이 없음). `ConnectRequest`에 애초에 provider 필드가 없었기 때문이다. 지금까지는 프로바이더가 하나뿐이라 문제가 드러나지 않았지만, Toss를 추가하는 순간 이 필드가 라우팅의 근거가 되므로 반드시 고쳐야 한다.

## Decision

1. **`BrokerageProvider`에 `TOSS` 추가**, `ConnectRequest`에 `provider: String`(필수) 필드 추가. 연동 시 사용자가 KIS/Toss 중 실제로 어느 증권사인지 명시하고, 이 값이 그대로 `BrokerageAccount.provider`에 저장된다(지금까지의 항상-MOCK 버그를 고침).

2. **`BrokerageClientRegistry` 도입** — `BrokerageService`가 인터페이스를 직접 주입받는 대신 `registry.get(account.provider)`로 매 호출마다 올바른 클라이언트를 얻는다. Mock/Real 스위칭은 레지스트리를 만드는 두 개의 `@ConditionalOnProperty` 빈 팩토리 메서드로 옮긴다:
   ```kotlin
   @Bean @ConditionalOnProperty(..., havingValue = "true", matchIfMissing = true)
   fun mockRegistry(mock: MockBrokerageClient) =
       BrokerageClientRegistry(BrokerageProvider.entries.associateWith { mock })

   @Bean @ConditionalOnProperty(..., havingValue = "false")
   fun realRegistry(kis: KisBrokerageClient, toss: TossBrokerageClient) =
       BrokerageClientRegistry(mapOf(KIS to kis, TOSS to toss))
   ```
   `KisBrokerageClient`/`MockBrokerageClient` 자체의 기존 `@ConditionalOnProperty`는 그대로 둔다 — 새 `TossBrokerageClient`도 KIS와 동일하게 `havingValue = "false"`로 게이팅한다. 즉 mock 모드에서는 KIS/Toss 계좌 모두 `MockBrokerageClient`가 대신 처리하고(로컬 개발 편의 유지), real 모드에서만 실제로 갈라진다.

3. **`BrokerageCredentials`에 `providerAccountRef: String? = null` 추가**, `BrokerageClient`에 `fun resolveAccountRef(token: BrokerageToken, accountNumber: String): String? = null` 기본 메서드 추가. KIS는 계좌번호 문자열만으로 CANO/ACNT_PRDT_CD를 계산하므로 오버라이드하지 않는다. `TossBrokerageClient`만 이를 오버라이드해 `GET /api/v1/accounts`로 `accountSeq`를 조회해 반환한다. `connect()` 시점에 한 번만 조회해 `BrokerageAccount.providerAccountRef` 컬럼(신규, 평문 — accountSeq는 API 키 없이는 무의미한 내부 참조값이라 암호화 대상 아님)에 저장하고, 이후 모든 Toss 호출은 저장된 값을 재사용한다 — `ACCOUNT` rate limit 그룹이 초당 1회로 가장 빡빡해서 매 호출마다 재조회하면 안 된다.

4. **`TossBrokerageClient` 신규 구현** — `KisBrokerageClient`와 동일한 구조(RestClient, `resilience4j` 서킷브레이커 `cb.executeCallable`, `CallNotPermittedException`/`RestClientException` catch)로 OAuth2 토큰 발급, 주문 생성/조회, 잔고 조회를 구현한다. `clientOrderId`는 `UUID.randomUUID().toString()`(정확히 36자, 패턴 만족)로 매 주문마다 생성해 멱등성을 보장한다. `confirmHighValueOrder=true`를 항상 전달한다 — 실제 금액 상한 체크는 이미 ADR-025의 사전 리스크 게이트가 담당하므로, 여기서는 브로커 측 착오주문 방지 확인을 무조건 통과시켜 이중 차단으로 사용자를 막지 않는다.

5. **재연동/증권사 교체 처리** — 사용자가 이미 활성 계좌가 있는 상태에서 다른 증권사(또는 다른 계좌번호)로 연동하면, 기존 활성 계좌는 비활성화(`isActive=false`)하고 새 (provider, accountNumber) 조합의 계좌를 찾거나 새로 만든다. `BrokerageAccountRepository`에 `findByUserIdAndProviderAndAccountNumber`를 추가해, 예전에 썼다가 비활성화한 계좌로 다시 돌아올 때 `UNIQUE(user_id, provider, account_number)` 위반 없이 그 행을 재활성화한다.

6. **`IdempotencyFilter`에 `/api/brokerage/orders` 추가** — 이 엔드포인트는 실제 돈이 걸린 주문 제출인데도 지금까지 인바운드 멱등성 보호 대상(`/api/paper/buy`, `/api/matching/orders` 등)에서 빠져 있었다. Toss의 `clientOrderId`(모나티커→Toss, 브로커 측 중복 주문 방지)와는 별개 레이어다 — 이건 클라이언트(브라우저)→모나티커 사이의 네트워크 재시도로 인한 중복 제출을 막는다.

7. **`docs/external-apis.md` WebSocket 서술 정정** — "추후 지원" → 이미 AsyncAPI 스펙 및 `wss://openapi-ws.tossinvest.com/ws/v1`이 존재함을 반영. 시세 스트리밍 자체는 이번 ADR 범위 밖.

## Reasons

- **레지스트리 vs 매 서비스 메서드에서 `if (provider == TOSS) ... else ...` 분기**: 후자는 `BrokerageClient` 인터페이스의 존재 의미(어댑터 패턴)를 무력화하고 새 프로바이더 추가마다 `BrokerageService` 본문을 계속 고쳐야 한다. 레지스트리는 `BrokerageService`를 프로바이더 개수와 무관하게 만든다.
- **Mock/Real 스위칭을 레지스트리 빈 팩토리로 옮긴 이유**: 기존 두 클라이언트의 `@ConditionalOnProperty`를 건드리지 않고(회귀 위험 최소화) 그대로 재사용하면서, "새 프로바이더 추가 시 이 이름 패턴을 따른다"는 `CircuitBreakerConfiguration.kt`의 기존 주석과 같은 결의 확장이 된다.
- **`providerAccountRef`를 연동 시점에 한 번만 조회해 저장**: Toss의 `ACCOUNT` rate limit 그룹이 초당 1회로 전체 그룹 중 가장 낮다 — 주문/잔고 조회마다 계좌 조회를 반복하면 실사용 트래픽에서 바로 한도에 걸린다.
- **`confirmHighValueOrder` 무조건 `true`**: 리스크 게이트를 이미 통과한 주문을 브로커가 다시 "정말 확인했습니까"로 막으면, 사용자 입장에서 두 번째 불필요한 차단 사유가 생긴다. 금액 상한 정책은 `RiskLimit`(사용자별 설정 가능) 한 곳에만 있어야 한다.

## Consequences

- `BrokerageService`의 생성자 시그니처가 `BrokerageClient` → `BrokerageClientRegistry`로 바뀐다. 컨트롤러/다른 모듈은 `BrokerageService`만 참조하므로 영향 없음 — 테스트만 갱신 필요.
- Toss는 모의투자 서버가 없다 — KIS처럼 "스펙상 맞을 가능성이 높다"는 문구조차 실계좌 없이는 한 단계 더 불확실하다. 첫 실계좌 검증 전까지는 OpenAPI 스펙 대조만으로 정확성을 주장한다.
- `BrokerageClient.cancelOrder`가 인터페이스에 없어 `BrokerageService.cancelOrder()`가 로컬 상태만 CANCELLED로 바꾸고 브로커에는 취소 요청을 보내지 않는 기존 결함은 이번에도 고치지 않는다 — KIS와 동일하게 남아 있는 문제이고, 이번 ADR의 스코프(provider 라우팅 도입)와 독립적이라 별도 라운드로 미룬다.
- Toss의 OCO/OTO 조건부 주문(`/api/v1/conditional-orders`), 해외주식(US) 지원, 소수점 수량 주문, 클라이언트 사이드 그룹별 rate limit(토큰 버킷)은 이번 범위 밖이다 — `BrokerageClient` 인터페이스가 표현하는 "단순 시장가/지정가 매수매도" 수준만 구현해 KIS와 기능 동등성만 맞춘다.
- 프론트엔드 연동 화면(`/brokerage/connect`)은 지금 provider 선택 UI가 없다 — 이번 라운드는 백엔드만 다루고, 프론트엔드는 후속 라운드로 넘긴다(ADR-025 이후 프론트엔드를 별도 라운드로 진행한 것과 동일한 순서).

## Revisit When

- Toss가 조건부 주문(OCO/OTO)이나 해외주식 지원을 제품 요구사항으로 올릴 때 — `BrokerageClient` 인터페이스 확장이 필요하다.
- 첫 실제 Toss 계좌로 E2E 주문을 실행할 때 — `docs/launch-plan.md`에 KIS와 동일한 검증 단계를 추가한다.
- `BrokerageClient.cancelOrder`를 실제로 브로커에 전달하도록 고칠 때 — KIS/Toss 양쪽에 동시에 적용해야 한다.
- 실시간 시세를 Toss WebSocket으로 전환할 때 — `market.ticks` 파이프라인(ADR-023 언급)과 별개 작업이다.
