# monticker — External APIs

> Read this when: implementing a collector worker, wiring up a new data provider, or selecting an API key to configure.

## Decision Criteria

| Factor | Notes |
|--------|-------|
| Real-time support | WebSocket or polling interval |
| Korean market coverage | KOSPI / KOSDAQ required |
| US market coverage | NASDAQ / NYSE optional for MVP |
| Cost | Free tier / per-call / monthly |
| Auth | API key, OAuth, or open |
| Reliability | SLA, uptime history |

---

## 1. Stock Price Data

### KIS Developers (한국투자증권 OpenAPI)

- URL: https://apiportal.koreainvestment.com
- Coverage: KOSPI, KOSDAQ, US markets
- Realtime: WebSocket (실시간 체결, 호가)
- REST: 현재가, 분봉, 일봉, 시장 지수
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

### **MVP Recommendation**

```
Korean markets:  KIS Developers (WebSocket + REST)
US markets:      Alpha Vantage or KIS US market API
EOD / history:   KRX 정보데이터시스템
```

---

## 2. News Data

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

### **MVP Recommendation**

```
Primary:    Naver News Search API  (국내 종목 뉴스 수집)
Supplement: BigKinds               (감성 분석 필요 시)
```

---

## 3. Disclosure Data (공시)

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

### **MVP Recommendation**

```
DART OpenAPI (필수)
```

---

## 4. Market Index / Sector Data

### KIS Developers

- 코스피, 코스닥, 섹터 지수 API 포함 (동일 API key 사용)

### KRX 정보데이터시스템

- 시장별 지수 히스토리

### **MVP Recommendation**

```
KIS Developers (지수 API 병행 사용)
```

---

## 5. AI / NLP

### Claude API (Anthropic)

- Use for: news summary, disclosure summary, sentiment keyword extraction, event importance scoring, beginner-friendly explanations
- Model recommendation: `claude-haiku-4-5-20251001` for high-volume batch tasks, `claude-sonnet-4-6` for quality-sensitive tasks
- Auth: API key
- Cost: per-token pricing (see https://www.anthropic.com/pricing)
- Notes: **AI Insight Worker의 기본 provider로 사용**. 투자 추천은 생성하지 않도록 system prompt에 명시.

### **MVP Recommendation**

```
Claude API (Anthropic)
- News / disclosure summary
- Sentiment keyword extraction
- Event importance scoring
```

---

## 6. Push Notifications

### Firebase Cloud Messaging (FCM)

- Coverage: Android + Web
- Auth: Firebase project service account
- Cost: Free

### Apple Push Notification Service (APNs)

- Coverage: iOS
- Auth: Apple Developer certificate
- Cost: Free (Apple Developer Program membership required)

### **MVP Recommendation**

```
Mobile MVP 이전까지는 불필요.
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

MVP starts with real providers. If rate-limited during development, swap to a `MockStockPriceProvider`.

---

## Setup Checklist

| Provider | Action Required |
|----------|----------------|
| KIS Developers | 계좌 개설 → API 신청 → App key 발급 |
| KRX 데이터시스템 | 회원가입 → API key 발급 |
| Naver Search API | developers.naver.com 앱 등록 → Client ID/Secret |
| DART OpenAPI | opendart.fss.or.kr 회원가입 → API key 발급 |
| Anthropic Claude | console.anthropic.com → API key 발급 |
| Firebase (FCM) | Firebase 프로젝트 생성 → 서비스 계정 키 발급 |

All keys must be stored in `.env` (never committed). Reference via environment variables only.
