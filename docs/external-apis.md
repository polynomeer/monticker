# monticker — External APIs

> Read this when: implementing a collector worker, wiring up a new data provider, or selecting an API key to configure.

**Product stage:** MVP is complete, monticker is commercializing ([ADR-023](decisions/023-commercialization-pivot.md)). "Recommendation" sections below are the baseline/default choice for commercial operation, not an MVP-only stopgap — real-provider integration (not Mock) is the target.

## Decision Criteria

| Factor | Notes |
|--------|-------|
| Real-time support | WebSocket or polling interval |
| Korean market coverage | KOSPI / KOSDAQ required |
| US market coverage | NASDAQ / NYSE optional (Toss covers KR+US in one API — see §2) |
| Cost | Free tier / per-call / monthly |
| Auth | API key, OAuth, or open |
| Reliability | SLA, uptime history |

---

## 1. Stock Price Data

### KIS Developers (한국투자증권 OpenAPI)

- URL: https://apiportal.koreainvestment.com
- Coverage: KOSPI, KOSDAQ, US markets
- Realtime: WebSocket (실시간 체결, 호가)
- REST: 현재가, 분봉, 일봉, 시장 지수, 투자자별 매매동향(개인/외국인/기관 순매수, `inquire-investor`, [ADR-017](decisions/017-investor-flow-kis-integration.md)), 시가총액/PER/PBR(`inquire-price` 응답에 이미 포함, [ADR-018](decisions/018-stock-fundamentals-kis-reuse.md))
- Auth: API key (계좌 개설 필요)
- Cost: Free (개인 계좌 기준)
- Notes: **한국 개인 개발자가 가장 많이 사용하는 국내 증권 API**

### LS증권 OpenAPI (LS証券)

- URL: https://openapi.ls-sec.co.kr
- Coverage: KOSPI, KOSDAQ
- Realtime: WebSocket
- Auth: API key (계좌 개설 필요)
- Cost: Free

### Korea Exchange (KRX) 정보데이터시스템

- URL: http://data.krx.co.kr
- Coverage: 전 종목 EOD 데이터, 지수
- Realtime: No (T+1 EOD only)
- Auth: 회원가입 후 API key
- Cost: Free
- Notes: 일봉 히스토리, 종목 마스터 데이터 수집에 적합

### Alpha Vantage

- URL: https://www.alphavantage.co
- Coverage: US markets, limited Korean support
- Realtime: polling (5min delay on free tier)
- Auth: API key
- Cost: Free (25 req/day) / paid plans available
- Notes: US 종목 데이터 보완용으로 적합

### Yahoo Finance (unofficial)

- No official API. Use via `yfinance` Python library or third-party wrappers.
- Not recommended for production — no SLA, ToS restrictions.

### **Recommendation**

```
Korean markets:  KIS Developers (WebSocket + REST)
US markets:      Alpha Vantage or KIS US market API
EOD / history:   KRX 정보데이터시스템
```

---

## 2. Brokerage / Order Execution (실주문 체결)

**BYOK model** ([ADR-023](decisions/023-commercialization-pivot.md), [Architecture § Brokerage Adapter](architecture.md#brokerage-adapter--byok-model)): monticker holds no brokerage license itself. Every provider below is called with credentials the end user issues on their own brokerage account — monticker is the API client, never the broker of record.

### KIS Developers (한국투자증권 OpenAPI) — already integrated

- Already used for price/investor-flow/fundamentals collection (see §1 below) and has an order-execution client (`KisBrokerageClient`, `backend/api/.../brokerage/infrastructure/`).
- Order-related: 주문 생성/정정/취소, 주문 체결 조회, 잔고/매수가능금액 조회.
- Auth: per-user appKey/appSecret (계좌 개설 필요).

### Toss Securities Open API (토스증권 Open API) — order execution integrated (ADR-026); market data still planned

- URL: https://developers.tossinvest.com/docs
- Coverage: KR + US stocks in one API — 현재가/호가/체결/캔들, 보유자산, 주문(정정·취소 포함), 조건주문(SINGLE/OCO/OTO), 환율, 시장 캘린더, 투자자별 매매동향, 공매도·신용·대차.
- Realtime: both REST and WebSocket exist today — confirmed via the official OpenAPI/AsyncAPI specs (`wss://openapi-ws.tossinvest.com/ws/v1`), correcting an earlier note here that WebSocket was still "추후 지원" (ADR-026 verified this against the published spec directly). **Design any market-data adapter behind an interface anyway** so a REST-polling implementation can be swapped for WebSocket without touching consumers (mirrors the `StockPriceProvider` interface pattern below) — order execution (ADR-026) does not touch market-data streaming, so this remains unimplemented.
- Auth: per-user API key, plus a `clientOrderId`-style idempotency mechanism for order submission — reuse the existing `X-Idempotency-Key` / `IdempotencyFilter` pattern (`common/idempotency/`) rather than inventing a second one.
- Rate limiting: per-endpoint-group limits, current usage returned in response headers — wrap `TossBrokerageClient` with a token bucket / backoff and register a named resilience4j circuit breaker (`"toss"`) in `CircuitBreakerConfiguration`, the way `TradingServiceClient`/`YahooFinanceOrderBookProvider` already do. Do not copy `KisBrokerageClient`'s current lack of one.
- Implementation shape: implement the existing `BrokerageClient` interface (`brokerage/infrastructure/BrokerageClient.kt`) — `BrokerageService` depends on the interface only, so no other code changes. Conditional-order (OCO/OTO) support needs new interface methods since `BrokerageClient` currently has none for it.

### **Recommendation**

```
실주문 실행:  KIS + Toss — both wired via `BrokerageClientRegistry` (ADR-026), same `BrokerageClient` interface
공통 원칙:    BYOK — 사용자 계좌 API 키만 사용, monticker 명의 주문 없음
```

---

## 3. News Data

### Naver News Search API (네이버 검색 API)

- URL: https://developers.naver.com/docs/serviceapi/search/news/v1/news.md
- Coverage: 국내 주요 언론사 전체
- Realtime: polling (최신순 정렬)
- Auth: Naver Developer 앱 등록 후 Client ID/Secret
- Cost: Free (일 25,000건)
- Notes: **종목명 기반 뉴스 수집에 가장 실용적인 선택**. 제목+요약+URL+발행시각 제공.

### BigKinds (한국언론진흥재단)

- URL: https://www.bigkinds.or.kr
- Coverage: 54개 주요 언론사
- Realtime: No (수 시간 지연)
- Auth: 회원가입 후 API key
- Cost: 무료 (학술/비영리) / 상업용 별도 협의
- Notes: 감성 분석, 키워드 클러스터링 기능 내장. 분석용으로 유용.

### NewsAPI

- URL: https://newsapi.org
- Coverage: 영문 뉴스 위주, 국내 언론 일부
- Auth: API key
- Cost: Free (개발용, 100 req/day) / paid
- Notes: 영문 뉴스 보완용. 국내 주요 뉴스 커버리지 미흡.

### **Recommendation**

```
Primary:    Naver News Search API  (국내 종목 뉴스 수집)
Supplement: BigKinds               (감성 분석 필요 시)
```

---

## 4. Disclosure Data (공시)

### DART OpenAPI (금융감독원 전자공시시스템)

- URL: https://opendart.fss.or.kr
- Coverage: 전 상장사 공시 (KOSPI / KOSDAQ / KONEX)
- Realtime: 공시 등록 후 수 분 내 조회 가능
- Auth: API key (금감원 회원가입 후 발급, 무료)
- Cost: Free
- Endpoints:
  - `공시검색`: 전체 공시 목록, 종목별 필터
  - `기업개황`: 종목 기본 정보
  - `재무정보`: 재무제표
- Notes: **국내 공시 데이터의 유일한 공식 소스**. 반드시 사용.

### **Recommendation**

```
DART OpenAPI (필수)
```

---

## 5. Market Index / Sector Data

### KIS Developers

- 코스피, 코스닥, 섹터 지수 API 포함 (동일 API key 사용)

### KRX 정보데이터시스템

- 시장별 지수 히스토리

### **Recommendation**

```
KIS Developers (지수 API 병행 사용)
```

---

## 6. AI / NLP

### Claude API (Anthropic)

- Use for: news summary, disclosure summary, sentiment keyword extraction, event importance scoring, beginner-friendly explanations
- Model recommendation: `claude-haiku-4-5-20251001` for high-volume batch tasks, `claude-sonnet-4-6` for quality-sensitive tasks
- Auth: API key
- Cost: per-token pricing (see https://www.anthropic.com/pricing)
- Notes: **AI Insight Worker의 기본 provider로 사용**. 투자 추천은 생성하지 않도록 system prompt에 명시.

### **Recommendation**

```
Claude API (Anthropic)
- News / disclosure summary
- Sentiment keyword extraction
- Event importance scoring
```

---

## 7. Push Notifications

### Firebase Cloud Messaging (FCM)

- Coverage: Android + Web
- Auth: Firebase project service account
- Cost: Free

### Apple Push Notification Service (APNs)

- Coverage: iOS
- Auth: Apple Developer certificate
- Cost: Free (Apple Developer Program membership required)

### **Recommendation**

```
모바일 출시 이전까지는 불필요.
Web push (FCM) → 모바일 추가 시 APNs 연동.
```

---

## Provider Interface Design

All external providers must be hidden behind an interface. This allows swapping providers without changing business logic.

```kotlin
interface StockPriceProvider {
    fun getCurrentPrice(symbol: String, market: Market): PriceTick
    fun subscribeRealtime(symbols: List<String>, handler: TickHandler)
}

interface NewsProvider {
    fun fetchLatest(query: String, from: Instant): List<RawNews>
}

interface DisclosureProvider {
    fun fetchLatest(from: Instant): List<RawDisclosure>
}
```

Use real providers by default. If rate-limited during development, swap to a `MockStockPriceProvider`.

---

## Setup Checklist

| Provider | Action Required |
|----------|----------------|
| KIS Developers | 계좌 개설 → API 신청 → App key 발급 |
| Toss Securities Open API | developers.tossinvest.com 앱 등록 → API key 발급 (사용자별 BYOK — 서비스 소유 키가 아니라 각 사용자가 본인 계좌로 발급) |
| KRX 데이터시스템 | 회원가입 → API key 발급 |
| Naver Search API | developers.naver.com 앱 등록 → Client ID/Secret |
| DART OpenAPI | opendart.fss.or.kr 회원가입 → API key 발급 |
| Anthropic Claude | console.anthropic.com → API key 발급 |
| Firebase (FCM) | Firebase 프로젝트 생성 → 서비스 계정 키 발급 |

All service-owned keys must be stored in `.env` (never committed), referenced via environment variables only. User-owned BYOK broker credentials (KIS/Toss) are a different case — they belong to each user's account and must be encrypted at rest in the database, never in `.env` (see [ADR-023](decisions/023-commercialization-pivot.md)).
