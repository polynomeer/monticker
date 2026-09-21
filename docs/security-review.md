# monticker — 시큐어 코딩 / 보안 설계 점검

> Read this when: "지금 상태로 실브로커 주문을 열어도 되는가"를 판단할 때, 또는 침투테스트·
> 법률 검토 전에 코드 레벨 보안 상태를 확인할 때. 장애 대응 관점은 [resilience-plan.md](resilience-plan.md),
> 출시 체크리스트(법률/컴플라이언스 포함)는 [launch-plan.md](launch-plan.md), 결정 이력은
> [decisions/](decisions/).

작성일: 2026-09-21 · 기준 커밋: `bffb1ab` · 상태: **점검 결과 + 개선방안**

---

## 0. 판정 요약

**결론: 설계 철학은 대체로 건전하지만, 세 가지 결함이 "왜 안전한지"를 무의미하게 만든다.**

이 코드베이스는 실제로 보안을 신경 쓴 흔적이 많다 — AES-256-GCM 자격증명 암호화(랜덤 IV,
매번 다름), refresh token 해시 저장 + 서버 사이드 회전/폐기, `userId`를 클라이언트 입력이
아닌 JWT에서만 도출하는 일관된 패턴, ADR-025의 리스크 게이트를 조건부주문·리밸런싱까지
재사용하는 설계, AI 주문 제안이 구조적으로 직접 주문을 낼 수 없게 막아둔 것 등. `docs/launch-plan.md`
Phase 2("보안 리뷰 완료")에서 실제로 취약점 2건(평문 refresh token, 누락된 로그아웃 엔드포인트)을
찾아 고친 이력도 있다.

문제는 **그 좋은 설계 중 상당수가 지금 배포 경로에서는 실제로 작동하지 않는다**는 것이다:

| # | 문제 | 결과 | 근거 |
|---|------|------|------|
| **S1** | **`SPRING_PROFILES_ACTIVE=prod`를 켜는 배포 경로가 리포 어디에도 없다** | JWT 서명 키와 브로커리지 자격증명 암호화 키가 **공개 저장소에 커밋된 하드코딩 기본값 그대로** 라이브에 올라간다 | `application.yml`만 로드되고 `application-prod.yml`은 죽은 설정 |
| **S2** | **JWT 시크릿이 하드코딩되어 있다 (`${VAR:default}`조차 아님)** | S1과 결합 시 — 아무나 임의 userId·`role=ADMIN`으로 유효 서명된 토큰을 위조 가능. 인증 전체 우회 | `application.yml:238` 리터럴 문자열 |
| **S3** | **브로커리지 BYOK 자격증명 암호화 키가 공개 저장소에 커밋된 실사용 가능한 키** | S1과 결합 시 — DB 접근 권한이 있는 누구나 모든 사용자의 실제 KIS/Toss 액세스 토큰을 복호화 가능 | `application.yml:234` |
| **S4** | **Access/Refresh 토큰이 브라우저 `localStorage`에 저장** | XSS 1건이면 최대 7일간 지속되는 계정 탈취(실주문 포함), 로그아웃도 서버 폐기를 호출 안 함 | `apps/web/src/services/auth.ts`, 3개 독립 점검에서 동일하게 발견 |
| **S5** | **실브로커 주문 API(`POST /api/brokerage/orders`)에 입력 검증이 전혀 없다** | `side`에 `"BUY"`가 아닌 아무 문자열이나 보내면 조용히 SELL로 처리됨, 수량 음수 체크 없음, 로컬 DB에 없는 종목이면 ADR-025 리스크 게이트 자체를 건너뜀 | `BrokerageController.kt`/`BrokerageService.kt`/`KisBrokerageClient.kt` |

**S1 하나를 고치면 S2·S3와 그로 인한 파생 문제(§2.4 Mock 클라이언트가 기본 활성화되는 문제 포함)가
동시에 해결된다 — 가장 레버리지 높은 단일 수정.**

이 5개는 전부 이번 점검에서 코드를 직접 읽어 확인했고, 그 중 S1~S5(§2의 Critical 3건에 대응)는
아래 §1 방법론에서 밝힌 대로 내가 직접 `grep`/`Read`로 재검증했다. 아직 실브로커(KIS/Toss)
연동은 ADR-025/026/027/028이 스스로 인정하듯 **실계좌로 한 번도 검증된 적이 없다** — 이 문서의
발견 사항과 별개로 그 자체가 열린 리스크다.

---

## 1. 방법론

Kotlin/Spring Boot 백엔드 4개 모듈(api/worker/trading-service/quant-engine) + Next.js 프론트엔드를
대상으로 5개 영역에 독립된 코드 리뷰를 병렬로 수행했다. 각 리뷰는 실제 소스를 읽고, 파일:라인
단위 근거와 구체적인 익스플로잇 시나리오가 있는 것만 발견으로 인정하도록 지시했다 — "이럴 수도
있다" 식의 추측은 "unverified, needs runtime check"로 별도 표시하게 했다.

| 영역 | 커버리지 |
|---|---|
| 인증/세션/인가 | `auth/**`, `SecurityConfig`, `RateLimitFilter`, `IdempotencyFilter`, IDOR 스팟체크(brokerage/watchlist/alert/wallet) |
| 브로커리지 BYOK 시크릿 & 주문 안전성 | `brokerage/**`, `EncryptedStringConverter`, ADR-025/026/027/028/032/034/036, AI 주문 제안 |
| 인젝션 & 입력 검증 | 4개 백엔드 모듈의 raw JdbcTemplate 호출부 202곳 전수 조사, ES 쿼리, LLM 프롬프트, Kafka 역직렬화 |
| 웹 프론트엔드 | 토큰 저장/전송, XSS(`dangerouslySetInnerHTML` 등 전수), CSP, BYOK 폼, 의존성 |
| 인프라/시크릿/운영 | `.env.example`, `application*.yml`의 모든 `${VAR:default}` 패턴, docker-compose, K8s manifest, actuator 노출, 로깅 |

그 위에 기존 문서(`launch-plan.md`, `resilience-plan.md`, ADR-025/026/027/035/036, `legal-review-brief.md`)를
먼저 읽어 "이미 알려진 것"과 "새로 발견된 것"을 구분했다. **Critical로 분류한 3건(S2/S3/S5의 근거)은
서브 리뷰 결과를 그대로 믿지 않고 내가 직접 해당 파일을 다시 읽어 재검증했다** — 나머지 발견은
단일 또는 이중 리뷰에서 나온 것으로, 신뢰도는 높지만 이 문서 작성자가 라인 단위로 재확인하진
않았다.

---

## 2. Critical

### C1 — JWT 서명 키와 브로커리지 암호화 키가 하드코딩된 채 실제로 라이브에 올라간다

**근거**
```yaml
# backend/api/src/main/resources/application.yml:238
jwt:
  secret: monticker-dev-secret-key-must-be-at-least-32-bytes-long   # 환경변수 오버라이드 불가 — 리터럴

# backend/api/src/main/resources/application.yml:234
credential-encryption-key: ${CREDENTIAL_ENCRYPTION_KEY:HSKZ1kiMBZ80UfTmpyhmFXh6TtteeeweRhbngzaD4yk=}
```
두 값 다 **git 히스토리에 커밋되어 있어 이 MIT 라이선스 공개 저장소를 clone하는 누구나 알 수 있다.**
`application-prod.yml`은 `jwt.secret: ${JWT_SECRET}`(기본값 없음, 없으면 기동 실패)과
`credential-encryption-key: ${CREDENTIAL_ENCRYPTION_KEY}`로 올바르게 이 값들을 무력화하도록
작성돼 있지만, 이 파일은 Spring profile `prod`가 활성화됐을 때만 로드된다.

리포 전체에서 `SPRING_PROFILES_ACTIVE`를 검색하면:
```
docker-compose.yml:419  SPRING_PROFILES_ACTIVE: release   # Pinpoint APM 컨테이너용, 무관
docker-compose.yml:442  SPRING_PROFILES_ACTIVE: release   # 위와 동일
```
`docker-compose.yml`의 api/worker 서비스, `infra/k8s/base/*.yaml`, `infra/k8s/overlays/prod/`,
`backend/api/Dockerfile` **어디에도 `api`/`worker`/`quant-engine`/`trading-service`용
`SPRING_PROFILES_ACTIVE=prod`를 설정하는 곳이 없다.** `docs/deployment.md`가 bare
`java -jar` 배포 시 이걸 설정하라고 문서화는 해뒀지만, 실제로 커밋된 배포 산출물(docker-compose,
K8s manifest)에는 반영되지 않았다.

**공격 시나리오**
1. **인증 완전 우회 (JWT 위조)**: 운영자가 리포에 체크인된 `docker-compose.yml` 또는
   `infra/k8s/base`를 그대로 배포한다고 가정. `prod` 프로파일이 안 켜지므로 `jwt.secret`은
   `monticker-dev-secret-key-must-be-at-least-32-bytes-long`으로 고정된다. 이 문자열은
   공개돼 있으므로, 공격자는 `JwtTokenProvider.kt`와 동일한 HS384로 `{"sub":"1","role":"ADMIN",...}`
   같은 클레임을 직접 서명해서 **임의 사용자, 임의 role로 유효한 토큰을 만들 수 있다.**
   `JwtAuthenticationFilter`는 서명이 맞으면 그대로 `SecurityContext`를 채운다 — IDOR 방어(§3의
   "잘 된 부분" 참고)가 아무리 견고해도 인증 자체가 위조되면 전부 무의미하다.
2. **모든 사용자의 브로커리지 자격증명 복호화**: 같은 이유로 `CREDENTIAL_ENCRYPTION_KEY`도
   공개된 Base64 문자열이 실제로 쓰인다. `EncryptedStringConverter`의 AES-256-GCM 구현 자체는
   정확하지만(매 암호화마다 랜덤 12바이트 IV, 128비트 태그), 키가 공개면 DB 백업 유출, 다른 취약점을
   통한 데이터 노출, 또는 내부자 접근 등 어떤 경로로든 DB에 접근할 수 있는 사람은 모든 사용자의
   실제 KIS/Toss `accessToken`/`appKey`/`appSecret`을 복호화해서 그 계좌로 직접 주문을 낼 수 있다.
3. **파생 문제**: 같은 프로파일 공백 때문에 `app.brokerage.mock.enabled`(기본값 `true`,
   `application-prod.yml`에서만 `false`로 강제)도 켜진 채로 남는다 — §2의 C3, §4의 H1 참고.

**심각도**: Critical. 실행 가능성은 "공개 저장소를 그대로 배포"라는 흔한 운영 실수 하나뿐이고,
영향은 인증 전체 우회 + 모든 사용자의 실계좌 자격증명 노출이다.

**개선방안**
- 즉시: `JWT_SECRET`, `CREDENTIAL_ENCRYPTION_KEY`를 git 히스토리에서 제거하고(값 자체 폐기,
  `git filter-repo`/BFG 등으로 히스토리 정리 검토), 두 값 다 새로 발급.
- 구조적: 프로파일 활성화에 의존하지 말 것 — `application.yml`(기본 프로파일)에서부터
  `jwt.secret: ${JWT_SECRET}`(기본값 없음)으로 바꿔서, prod든 dev든 환경변수가 없으면
  **기동 자체가 실패**하게 만든다. 로컬 dev 편의성은 `.env`/`docker-compose.yml`에 dev 전용
  랜덤 키를 넣어서 유지하면 된다 — "이 키가 공개돼도 안전한 환경(dev)"과 "반드시 비밀이어야 하는
  환경(prod)"을 프로파일 스위치 하나로 구분하는 현재 설계 자체가 실수 유발형이다.
- 배포 산출물: `docker-compose.yml`/`infra/k8s/base/api.yaml`에 `SPRING_PROFILES_ACTIVE=prod`를
  명시적으로 추가하거나(bare `java -jar`용), 위 구조적 수정으로 프로파일 여부와 무관하게 안전하게
  만드는 쪽을 우선한다.
- 부트 타임 가드: `jwt.secret`이 `application.yml`에 박힌 리터럴 dev 기본값과 문자열이 같으면
  `dev`/`local` 프로파일이 아닌 한 `ApplicationContext` 기동을 막는 `@PostConstruct` 체크 추가.

---

### C2 — Access/Refresh 토큰이 `localStorage`에 저장되고, OAuth 리다이렉트 URL에도 노출된다

3개의 독립된 점검(인증/세션, 웹 프론트엔드, 인프라)이 각각 코드를 직접 읽고 동일한 결론에
도달했다 — 코드베이스 자체에 이미 "refresh token은 반드시 HttpOnly 쿠키로, localStorage 금지"라는
명시된 정책이 있는데 실제 구현이 이를 정면으로 어기고 있다.

**근거**
```ts
// apps/web/src/services/auth.ts:66-68
export function saveTokens(tokens: AuthTokens) {
  localStorage.setItem("accessToken", tokens.accessToken);
  localStorage.setItem("refreshToken", tokens.refreshToken);
}
```
```ts
// apps/web/src/services/api.ts:24
const refreshToken = typeof window !== "undefined" ? localStorage.getItem("refreshToken") : null;
```
```kotlin
// backend/api/.../auth/infrastructure/OAuth2SuccessHandler.kt:49-51
response.sendRedirect(
    "$baseUrl/oauth2/callback?accessToken=${tokens.accessToken}&refreshToken=${tokens.refreshToken}"
)
```
그리고 프론트엔드의 "로그아웃"은 서버 쪽 폐기를 호출하지 않는다:
```ts
// apps/web/src/hooks/useAuth.ts:20-24
const logout = () => {
  clearTokens();       // localStorage만 지움
  setIsLoggedIn(false);
  window.location.href = "/login";
};
```
백엔드에는 이미 `POST /api/auth/logout` → `AuthService.logout(refreshToken)`이 구현돼 있고
refresh token을 DB에서 삭제하는데(`AuthController.kt:62-64`), 프론트엔드가 이걸 호출하지 않는다.

**공격 시나리오**
- `refresh-token-expiry-ms: 604800000`(7일) 동안 유효한 refresh token을, SPA 어디에서든 XSS 한
  건(의존성 공급망 침해, 브라우저 확장 프로그램, 향후 추가될 뉴스/코멘트 렌더링 버그 등)이 나면
  `localStorage.getItem("refreshToken")` 한 줄로 탈취 가능. 탈취된 토큰은 회전되긴 하지만
  재사용 탐지가 없어서(§4 H2) 공격자가 먼저 `/api/auth/refresh`를 호출해도 감지되지 않고, 그
  갱신된 체인으로 최대 7일간 지속적으로 "로그인 상태"를 유지하며 실브로커 주문까지 낼 수 있다.
- 사용자가 "로그아웃"을 눌러도 서버 쪽 토큰은 살아있으므로, 탈취된 토큰의 유효성에는 영향이 없다
  — 사용자가 취할 수 있는 유일한 실질적 방어가 작동하지 않는다.
- OAuth 로그인 경로(현재 코드는 있지만 provider 미설정으로 비활성 — `OAuth2SuccessHandler.kt`
  주석에 명시)는 provider가 연결되는 즉시 두 토큰을 그대로 URL 쿼리스트링에 실어 브라우저 히스토리,
  프록시/CDN 접근 로그, `Referer` 헤더에 노출시킨다. 이건 XSS 없이도 로그 접근 권한만으로 뚫리는
  별도 경로다.

**심각도**: Critical. 프로젝트 스스로 정한 정책 위반이고, 브로커리지 실주문 기능과 결합되면
탈취된 세션으로 실제 자금 이동이 가능하다.

**개선방안**
- Access/Refresh 토큰을 `HttpOnly; Secure; SameSite=Strict`(또는 Lax) 쿠키로 서버가 직접
  `Set-Cookie`하도록 변경 — `HttpCookieOAuth2AuthorizationRequestRepository.kt`가 OAuth
  핸드셰이크 쿠키에 이미 이 패턴을 올바르게 쓰고 있으므로 같은 패턴을 재사용하면 된다.
- OAuth 콜백은 URL에 토큰을 싣지 말고, 쿠키로 설정하거나 1회용 opaque code를 발급해 프론트엔드가
  POST로 교환하게 변경.
- `useAuth.logout()`이 `clearTokens()` 전에 반드시 `POST /api/auth/logout`을 호출하도록 수정
  (엔드포인트는 이미 존재 — 프론트 배선만 빠짐, 수정 자체는 작다).
- (§4 H2) refresh token 재사용 탐지: 이미 회전되어 사라진 토큰 해시가 다시 들어오면 해당
  사용자의 모든 세션을 강제 폐기.

---

### C3 — 실브로커 주문 API에 입력 검증이 전혀 없고, 미등록 종목이면 리스크 게이트를 건너뛴다

**근거**
```kotlin
// backend/api/.../brokerage/api/BrokerageController.kt:27-33
data class OrderRequest(
    val symbol: String,
    val side: String,        // 제약 없음 — "BUY" 외 아무 문자열이나 허용
    val orderType: String,
    val quantity: Int,       // 제약 없음 — 음수/0 허용
    val limitPrice: BigDecimal? = null,
)
// fun submitOrder(@RequestBody req: OrderRequest, ...)  ← @Valid 없음
```
```kotlin
// backend/api/.../brokerage/application/BrokerageService.kt:99-118
fun submitOrder(userId: Long, request: BrokerageOrderRequest): BrokerageOrder {
    val account = getAccount(userId)
    ...
    val stockId = resolveStockId(request.symbol)

    if (stockId != null) {
        ... riskChecker.checkBrokerageOrder(...)   // 실패하면 여기서 막힘
    } else {
        log.warn("리스크 체크 건너뜀 — 종목을 찾을 수 없음: symbol={}", request.symbol)
        // ↓ 그대로 통과
    }

    val result = client.submitOrder(credentials, request)   // stockId==null이어도 항상 실행됨
    ...
    OrderSide.valueOf(request.side)   // ← enum 검증은 여기, 이미 주문을 보낸 뒤
```
```kotlin
// backend/api/.../brokerage/infrastructure/KisBrokerageClient.kt:122,130
val trId = if (request.side == "BUY") "TTTC0802U" else "TTTC0801U"   // "BUY"가 아니면 전부 SELL
...
"ORD_QTY" to request.quantity.toString(),   // 음수든 0이든 그대로 KIS로
```
직접 확인: `submitOrder`/`BrokerageService.kt` 전체에 `require(...)` 호출은 소유권 체크
2건뿐, `quantity`나 `side`에 대한 가드는 없다. `OrderSide.valueOf(request.side)`는 118번
줄에서 브로커에 주문을 보낸 **이후**인 125번 줄에 있어서 사전 방어로 기능하지 못한다.

대조적으로 조건부주문(`ConditionalOrderService`)과 리밸런싱(`RebalanceExecutionService`)은
`resolveStockId`가 null이면 명시적으로 예외를 던지고 절대 `submitOrder`까지 안 간다 — 즉 이건
설계 의도가 아니라 수동 주문 엔드포인트 하나만 빠뜨린 것으로 보인다. 페이퍼 트레이딩 경로
(`OrderSagaOrchestrator.kt:84-90`)는 이미 quantity>0, side∈{BUY,SELL}, orderType 유효성,
종목 존재 여부를 다 체크하고 있으므로 — 이 검증 로직을 실브로커 경로에 옮기기만 하면 된다.

**공격 시나리오**
- `POST /api/brokerage/orders {"symbol":"005930","side":"XYZ","orderType":"MARKET","quantity":-100}`
  — 클라이언트 버그나 요청 변조로 `side`가 정확히 `"BUY"`가 아니면 **의도와 반대로 SELL 주문이
  실제 증권사에 나간다.** `quantity`가 음수여도 그대로 KIS API로 전달되는데, 이 경로는
  ADR-025/028이 스스로 인정하듯 실계좌로 검증된 적이 없어서 KIS 쪽이 어떻게 반응할지 코드만으로는
  알 수 없다.
- 로컬 `stocks` 테이블에 없는 종목(신규 상장, ETN 등)을 지정하면 일일 손실 한도·집중도·VaR·포지션
  수·시간당 주문 빈도를 검사하는 ADR-025 리스크 게이트 전체가 건너뛰어진다 — 제한 없는 크기/빈도의
  실주문이 감사 로그(`RiskCheckAuditLogger`)도 안 남긴 채 나간다.

**심각도**: Critical. 실제 자금이 걸린 주문 경로에 방어가 없다 — 권한 상승 없이 발생 가능하고
파급력은 직접적인 금전 손실이다.

**개선방안**
- `OrderRequest`에 Bean Validation 추가: `side`/`orderType`은 `@field:Pattern` 또는 enum
  타입으로 직접 받기, `quantity`는 `@field:Positive`, 컨트롤러에 `@Valid` 부여.
- `BrokerageService.submitOrder` 최상단에 `require(request.quantity > 0)`,
  `require(request.side in setOf("BUY","SELL"))` 같은 가드를 페이퍼 트레이딩(`OrderSagaOrchestrator`)과
  동일한 수준으로 추가 — 브로커 클라이언트 호출 전에.
- `resolveStockId(request.symbol) == null`이면 조건부주문/리밸런싱과 동일하게 **예외를 던지고
  주문을 막는다** — "리스크 체크 건너뜀" 경고 로그로 대체하지 않는다.
- (§4 H2) 이 패턴이 반복되지 않도록 39개 중 37개 컨트롤러에 Bean Validation이 없는 시스템적
  공백 자체를 별도 작업으로 닫는다.

---

## 3. High

### H1 — Mock 브로커리지 클라이언트가 실제 BYOK `appKey`를 평문 로깅

```kotlin
// backend/api/.../brokerage/infrastructure/MockBrokerageClient.kt:44
log.info("[MockKIS] 토큰 발급: appKey={}", appKey)
```
`MockBrokerageClient`는 `app.brokerage.mock.enabled`가 없으면(`matchIfMissing = true`) 기본
활성화되고, 이 값은 `application-prod.yml`에서만 `false`로 강제된다 — 즉 **C1과 동일한 프로파일
공백의 직접적인 파생 결과**로, 지금 배포 경로에서는 이 mock 클라이언트가 활성화된 채로 사용자가
`POST /api/brokerage/connect`에 입력한 진짜 KIS `appKey`를 애플리케이션 로그에 평문으로 남긴다
(`appSecret`은 로깅되지 않아 피해가 제한적이긴 하다). "시크릿·토큰·비밀번호는 절대 로깅하지 않는다"는
원칙 위반.

**개선방안**: `appKey`도 로깅 시 마스킹(끝 4자리만 노출 등)하거나 아예 로깅하지 않는다. C1 수정과
별개로 독립적으로 고쳐야 한다 — mock 클라이언트가 어떤 이유로든 다시 기본 활성화되더라도 이 로그
자체가 안전해야 한다.

### H2 — REST 컨트롤러 41개 중 39개에 Bean Validation이 없다

`AuthController`(`@field:Email`, `@field:Size`, `@field:NotBlank`)와 `AlertController` 정도만
`@Valid`를 쓰고, `BrokerageController`/`ConditionalOrderController`/`MatchingController`를
포함한 나머지는 서비스 계층 안쪽의 산발적인 `require()`에 기대거나(있으면 다행) 아예 없다.
`symbol`/`appKey`/`appSecret`/`accountNumber` 같은 문자열 필드에 길이 제한이 없어서, 대용량
문자열을 DB 컬럼이나 ES 인덱스에 그대로 밀어넣는 저장소 고갈성 공격도 이론상 가능하다. C3는 이
시스템적 공백의 가장 심각한 사례일 뿐, 근본 원인은 별도로 닫아야 한다.

**개선방안**: `AuthController`의 패턴(`@field:` 제약 + 컨트롤러 `@Valid`)을 표준으로 삼아
브로커리지/조건부주문/리밸런싱 컨트롤러부터 우선 적용, 이후 나머지로 확대.

### H3 — Refresh token 재사용(탈취) 탐지가 없다

회전(rotation) 자체는 잘 구현돼 있다(`AuthService.kt:105-121` — 매 refresh마다 기존 해시
삭제 후 재발급). 하지만 "이미 회전되어 사라진 토큰 해시가 다시 들어옴 = 탈취 신호"를 감지해서
해당 사용자의 전체 세션을 강제 폐기하는 로직이 없다. 지금은 그냥 일반적인 401("만료되었거나
유효하지 않은 refresh token입니다")만 반환하므로, 공격자가 피해자보다 먼저 회전시켜도 피해자
쪽에서는 단순 만료로만 보인다. C2가 고쳐지기 전까지는 특히 중요 — localStorage 노출이 남아있는
한 이게 유일한 사후 탐지 수단이 될 수 있다.

**개선방안**: `refresh()`에서 들어온 토큰이 서명은 유효하지만 DB에 해당 해시 행이 없는 경우(=
이미 회전되어 사라진 경우) 탈취로 간주하고 해당 `user_id`의 모든 `refresh_tokens`를 삭제.

### H4 — `/api/auth/resend-verification`에서 이메일 등록 여부가 노출된다

`forgotPassword`는 의도적으로 이메일 존재 여부와 무관하게 항상 같은 응답을 반환하도록 작성돼
있는데(`AuthService.kt:140-142`), `resendVerification`은 미등록 이메일에 대해 다른 메시지
("등록되지 않은 이메일입니다")를 던진다(`AuthService.kt:70-75`). `signup`도 마찬가지로 이미
사용 중인 이메일에 별도 메시지를 준다 — 이쪽은 일반적인 트레이드오프라 낮은 우선순위지만,
`resendVerification`은 `forgotPassword`와 정확히 같은 패턴이 이미 코드베이스에 있으므로 그대로
따라가면 되는 낮은 비용의 수정이다. 타겟 피싱/크리덴셜 스터핑 대상 이메일 목록 수집에 악용 가능.

**개선방안**: `resendVerification`도 `forgotPassword`와 동일하게 이메일 존재 여부와 무관한
제네릭 응답으로 통일.

---

## 4. Medium

| # | 문제 | 근거 | 비고 |
|---|------|------|------|
| M1 | 뉴스/이벤트 원문이 위생 처리 없이 LLM 프롬프트에 그대로 삽입됨(프롬프트 인젝션 표면) | `OrderProposalService.kt:139-175`, `StockSummaryService.kt:127-128` — `it.title`을 구분자 없이 프롬프트에 직접 보간 | 출력이 `OrderProposalSide.valueOf(...)`로 enum 강제되고 수량은 LLM이 절대 결정 못 함 + 사람이 반드시 승인해야 실행되므로 파급력은 "추천 방향을 편향시키는 정도"로 제한됨 |
| M2 | CSP `script-src`에 `'unsafe-inline' 'unsafe-eval'`이 무조건 허용 | `apps/web/next.config.ts` — Toss Payments SDK 로더 때문이라는 코드 주석 있음 | 지금은 앱 내에 XSS 벡터(`dangerouslySetInnerHTML` 등) 자체가 없어서 즉시 악용 불가하지만, 향후 뉴스/코멘트 렌더링 등이 추가되면 CSP가 방어막 역할을 전혀 못 함 — C2와 결합 시 피해 확대 |
| M3 | Redis/Elasticsearch가 인증 없이 기동됨 | `docker-compose.yml` — Redis `requirepass` 없음, ES `xpack.security.enabled=false` | rate limiting·로그인 실패 카운터·이메일 인증 토큰이 Redis에 있어, 인증 없는 Redis에 네트워크 접근 가능하면 이 방어들을 우회/조작 가능. `infra/k8s/base`에 Redis/ES manifest 자체가 없어 실제 운영 토폴로지는 unverified |
| M4 | HSTS/TLS 강제 설정이 리포 어디에도 없음 | `next.config.ts`, `infra/k8s/base/ingress.yaml` 둘 다 `Strict-Transport-Security` 없음, `ingress.yaml`에 `spec.tls`/ssl-redirect 없음 | CDN/로드밸런서가 앞단에서 처리할 가능성 있음 — unverified, 리포만으로는 확인 불가 |
| M5 | 브로커 연결 폼의 App Key 입력란이 마스킹 안 됨 | `apps/web/src/app/brokerage/connect/page.tsx` — `appSecret`만 `type="password"`, `appKey`는 평문 입력 | App Key 단독으로는 인증 불가하지만 식별 정보이므로 어깨너머 노출 최소화 차원에서 마스킹 권장 |
| M6 | 비밀번호 정책이 길이(8자) 외 복잡도 요구 없음 | `AuthController.kt` `@field:Size(min=8,max=100)`만 | BCrypt(적절) + rate limiting + 계정 잠금으로 상당 부분 완화됨 — 그래도 흔한 비밀번호 차단 정책은 추가 권장 |

---

## 5. Low

| # | 문제 | 근거 |
|---|------|------|
| L1 | Refresh token을 Access token 자리에 Bearer로 보내면 `JwtAuthenticationFilter`가 uncaught `TypeCastException`으로 500을 던짐(401이어야 함) | `JwtAuthenticationFilter.kt`/`JwtTokenProvider.getRole()` — refresh token엔 `role` 클레임이 없어 `null as String` 캐스팅 실패 |
| L2 | JWT에 `iss`/`aud` 클레임이 없음 | 단일 서비스 배포에선 저위험. `JWT_SECRET`이 환경 간 공유되지 않는지는 unverified |
| L3 | docker-compose.yml의 Postgres/Mongo/Grafana 기본 자격증명이 전부 `monticker`/`monticker` | 로컬 dev 전용 파일이라 그 자체는 문제 아님 — prod-adjacent 환경에 그대로 복붙되지 않도록 주의 환기 차원 |
| L4 | `EventTimelineService.getSectorSummary`의 `INTERVAL '$hours hours'`가 SQL 인젝션은 아니지만(Int 타입 보장) 서비스 자체에 범위 가드가 없어 호출부(`coerceIn(1,72)`)에만 의존 | 지금은 안전하지만 새 호출부가 같은 가드 없이 추가되면 비용이 큰 쿼리를 유발할 수 있음 |
| L5 | KIS `getOrderStatus`/settlement, Toss 전체 연동이 실계좌/데모 계정으로 검증된 적 없음 | ADR-025/026/028이 이미 자인한 내용 — 이 문서가 새로 발견한 건 아니지만 열린 채로 남아있는 실배포 차단 요인 |

---

## 6. 잘 되어 있는 부분

전부 문제라는 인상을 주지 않기 위해 명시적으로 남긴다 — 아래는 5개 영역 리뷰에서 실제로
코드를 읽고 확인한 것들이다.

- **인가(IDOR) 처리가 일관됨**: `userId`는 요청 바디/경로 파라미터가 아니라 항상
  `SecurityContext`(JWT)에서만 가져온다 — 전체 `@RestController`를 grep해서 확인. 리소스를
  다루는 서비스 메서드는 예외 없이 `require(order.userId == userId)` 류의 소유권 체크를 한다
  (`BrokerageService`, `WatchlistService`, `ConditionalOrderService` 등).
- **JWT 서명 검증 자체는 안전**: `Jwts.parser().verifyWith(key).build().parseSignedClaims(...)`
  구조상 `alg: none`이나 서명 없는 토큰을 원천적으로 거부한다. 문제는 키 값이지 검증 로직이 아니다.
- **Refresh token은 서버 사이드에서 해시 저장 + 회전 + 폐기**: SHA-256 해시로만 DB에 저장,
  매 refresh마다 회전, 로그아웃/비밀번호 재설정/계정 삭제 시 폐기 — `launch-plan.md` Phase 2가
  주장하는 수정이 서버 쪽에서는 실제로 확인된다. 남은 문제는 클라이언트 저장 방식(C2)이라는
  다른 레이어다.
- **CORS는 명시적 화이트리스트**: `allowedOriginPatterns` + 환경변수 기반, 와일드카드 없음.
- **Rate limiting이 2중 방어**: IP 기반(`RateLimitFilter`, 로그인/가입/refresh별 세분화) +
  이메일 기반 실패 카운터(`AuthService`, 5회/15분) — IP를 분산한 크리덴셜 스터핑도 이메일
  카운터가 잡는다.
- **Idempotency 키가 사용자별로 스코프됨**: `idempotency:{userId}:{key}` — 한 사용자가 다른
  사용자의 idempotency key를 재생/관찰할 수 없다. Redis 장애 시 fail-closed(503)로 주문 중복을
  막는 쪽을 택한 것도 결제/주문 시스템에 맞는 선택.
- **AI 주문 제안이 구조적으로 직접 주문을 못 낸다**: LLM은 `side`(방향)만 산출하고 수량/가격은
  절대 LLM이 정하지 않으며, 승인은 상태 플래그만 바꿀 뿐 `BrokerageService.submitOrder`로
  이어지는 코드 경로가 없다 — 프롬프트 인젝션(M1)이 성공해도 파급력이 "추천을 편향"시키는
  선에서 막힌다.
- **ADR-025 리스크 게이트가 조건부주문·리밸런싱에서 재사용됨** (C3에서 지적한 수동 주문 경로
  하나만 예외) — 자동화된 실주문 트리거들이 각자 리스크 체크를 재구현하지 않고 같은 관문을
  통과한다.
- **주문 취소가 실제로 브로커까지 도달**: 로컬 상태만 바꾸는 게 아니라 브로커 확인 후에만
  CANCELLED로 반영.
- **에러 응답이 내부 정보를 흘리지 않음**: `GlobalExceptionHandler`는 어떤 예외든 고정된 한국어
  메시지만 반환하고 스택트레이스·예외 클래스명은 서버 로그로만 간다.
- **actuator 노출이 적절히 제한됨**: `health,info,metrics,prometheus`만, `env`/`heapdump`
  없음, `/actuator/**`가 `permitAll`이긴 하지만 `infra/k8s/base/ingress.yaml`이 애초에 외부로
  라우팅하지 않음.
- **의존성 버전이 최신**: Spring Boot 3.5.0, Kotlin 1.9.25, jjwt 0.12.6, Next.js 15.5.25(예전에
  지적됐던 15.1.0의 CVE는 이미 패치됨).
- **SQL 인젝션 실사례 0건**: 4개 백엔드 모듈의 raw JdbcTemplate 호출 202곳을 전수 조사했고,
  문자열 보간이 있는 ~30곳도 전부 닫힌 enum/화이트리스트이거나 내부 상수였다 — `?` 바인딩이
  일관되게 지켜지고 있다. ES 쿼리(`NativeQuery` DSL)와 JPQL(`:param` 바인딩)도 마찬가지.
  Kafka 역직렬화도 평범한 Jackson POJO 매핑뿐, 폴리모픽 타이핑이나 Java 네이티브 역직렬화 없음.
- **모의투자(paper trading) 경로는 이미 제대로 검증함**: `OrderSagaOrchestrator`가 quantity>0,
  side/orderType 유효성, 종목 존재 여부를 다 체크 — C3에서 지적한 실브로커 경로가 베낄 수 있는
  참조 구현이 이미 같은 리포 안에 있다.
- **K8s 시크릿 관리 방향이 정직함**: `infra/k8s/base/secret.yaml`이 스스로 "플레이스홀더이니
  교체 필수"라고 문서화했고, External Secrets Operator(AWS/GCP/Vault) 템플릿을
  `infra/k8s/base/external-secrets-example/`에 준비해뒀으되 `kustomization.yaml`에서 의도적으로
  제외해 실수로 적용되지 않게 해뒀다.
- **OAuth2 CSRF 방어는 기본기가 맞음**: `HttpCookieOAuth2AuthorizationRequestRepository`가
  `state`를 HttpOnly 쿠키에 저장하고 Spring Security가 콜백에서 검증 — open redirect 없음,
  리다이렉트 대상이 항상 서버 설정값(`app.base-url`)이라 공격자가 조작 불가.

---

## 7. 우선순위별 조치 계획

`resilience-plan.md`의 P0/P1 표기 방식을 따른다.

### P0 — 실브로커 주문을 여는 것 자체를 막아야 하는 항목 (오늘 발생 가능)

| # | 항목 | 예상 작업 |
|---|------|----------|
| P0-1 | C1: `jwt.secret`/`credential-encryption-key`를 profile 무관하게 필수 환경변수로 전환, 기존 값 폐기·재발급 | `application.yml` 수정 1곳 + 키 재발급 + git 히스토리 정리 검토 |
| P0-2 | C2: 토큰을 HttpOnly 쿠키로 전환, OAuth 콜백 URL에서 토큰 제거, 프론트 로그아웃이 `/api/auth/logout` 호출하도록 배선 | 프론트 `auth.ts`/`api.ts`/`useAuth.ts` + 백엔드 `OAuth2SuccessHandler` |
| P0-3 | C3: `OrderRequest`에 Bean Validation, `BrokerageService.submitOrder`에 quantity/side 가드, 미등록 종목이면 예외로 차단 | `BrokerageController`/`BrokerageService` 수정, `OrderSagaOrchestrator` 패턴 재사용 |
| P0-4 | H1: Mock 클라이언트의 appKey 로깅 마스킹/제거 | `MockBrokerageClient.kt` 한 줄 |

### P1 — GA 전에 닫아야 하는 항목

| # | 항목 |
|---|------|
| P1-1 | H2: 39/41 컨트롤러의 Bean Validation 공백 — 브로커리지 계열부터 우선 |
| P1-2 | H3: Refresh token 재사용 탐지 → 탈취 시 전체 세션 강제 폐기 |
| P1-3 | H4: `resend-verification` 응답을 `forgot-password`와 동일한 제네릭 패턴으로 |
| P1-4 | M1: LLM 프롬프트에 삽입되는 외부 텍스트에 구분자/이스케이프 적용 |
| P1-5 | M3: Redis `requirepass` 설정, ES 인증 활성화(또는 실제 운영 토폴로지가 이미 managed/인증된 서비스인지 확인) |

### P2 — 여유 있을 때

| # | 항목 |
|---|------|
| P2-1 | M2: CSP `'unsafe-eval'`/`'unsafe-inline'`을 실제로 필요한 범위로 축소 (Toss 로더만 예외 처리) |
| P2-2 | M4: HSTS 헤더 + ingress TLS 강제 설정 (또는 CDN/LB가 이미 처리하는지 확인) |
| P2-3 | M5: 브로커 연결 폼 App Key 필드 마스킹 |
| P2-4 | M6: 비밀번호 복잡도 정책 추가 |
| P2-5 | L1: `JwtAuthenticationFilter`가 refresh token 오용 시 500 대신 401 반환하도록 |
| P2-6 | L2: JWT에 `iss`/`aud` 클레임 추가, 환경별 `JWT_SECRET` 분리 여부 확인 |

---

## 8. 기존 문서와의 관계

- **`launch-plan.md` Phase 2**("보안 리뷰 완료")가 고친 것(평문 refresh token → 해시,
  `/api/auth/logout` 신설, 로그인 브루트포스 방어)은 이번 점검에서도 **서버 사이드 기준으로는
  전부 유효함을 확인했다.** 이번에 새로 나온 문제(C1/C2/C3)는 그때 다루지 않았던 레이어다 —
  C1은 "키가 맞는 값으로 설정됐는가"가 아니라 "그 설정이 실제로 로드되는가", C2는 서버 저장
  방식이 아니라 클라이언트 저장 방식, C3는 인증/세션이 아니라 입력 검증이다.
- **`resilience-plan.md`의 C4**(KIS HTTP 클라이언트에 타임아웃 없음)는 가용성 문제로 분류돼
  있지만, 이 문서의 C3(입력 검증 없음)와 같은 코드 경로(`KisBrokerageClient`)를 건드리므로 두
  수정을 같이 진행하는 게 효율적이다.
- **ADR-025/027이 이미 인정한 잔여 리스크**(appSecret이 access token보다 장수명, 동시 토큰
  재발급 가능성, 실계좌 미검증)는 이 문서가 재론하지 않고 그대로 열린 항목으로 남겨둔다 —
  §5 L5 참고.
- **`legal-review-brief.md`**가 다루는 법률/라이선스 이슈(BYOK가 투자중개업 면허 대상인지 등)는
  이 문서의 범위 밖이다. 이 문서는 순수하게 코드 레벨 보안만 다룬다.
