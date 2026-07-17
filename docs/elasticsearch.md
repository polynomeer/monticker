# Elasticsearch 적용 현황

monticker 백엔드에 적용된 ES 인덱스·API·파이프라인 레퍼런스.

- **ES 버전**: 8.13.4
- **Spring Data ES**: 5.x
- **분석기**: nori_analyzer (한국어 형태소)
- **인덱스**: 6개 · **도메인**: 8개 · **신규 API**: 14개

---

## 공통 설계 원칙

| 원칙 | 내용 |
|---|---|
| **DB 우선** | DB가 항상 authoritative. ES는 검색 레이어이며 장애 시 DB로 fallback한다. |
| **Dual-write** | DB 저장 성공 후 ES 인덱싱. ES 실패는 `WARN` 로그만 남기고 트랜잭션에 영향 없음. |
| **@PostConstruct 동기화** | 앱 기동 시 최근 N건을 DB→ES 배치 동기화. ES가 비어 있어도 서비스 정상 동작. |
| **nori_analyzer** | 한국어 형태소 분석. `nori_readingform` + `lowercase` 필터 조합. |
| **userId 격리** | 사용자 범위 검색(관심종목·알림)은 `userId` filter 필수 적용. |

---

## 인덱스 목록

| 인덱스 | 도메인 | 분석기 | 초기 동기화 |
|---|---|---|---|
| `stocks` | 주식·스크리너 | nori + edge_ngram autocomplete | `StockIndexer` (전체) |
| `news_articles` | 뉴스 | nori + nori_readingform | `NewsIndexer` (최근 10,000건) |
| `stock_events` | 이벤트·공시 | nori + nori_readingform | `EventIndexer` (최근 10,000건) |
| `stock_summaries` | AI 요약 | nori + nori_readingform | 없음 (요청 시 생성·저장) |
| `watchlist_items` | 관심종목 | nori + nori_readingform | `WatchlistIndexer` (전체) |
| `alert_histories` | 알림 이력 | nori + nori_readingform | `AlertHistoryIndexer` (최근 50,000건) |

---

## 1. 주식 검색 — `stocks`

**관련 파일**
- `api/stock/infrastructure/StockDocument.kt`
- `api/stock/infrastructure/StockSearchRepository.kt`
- `api/stock/application/StockIndexer.kt`
- `api/stock/application/StockSearchService.kt`
- `api/src/main/resources/elasticsearch/stock-index-settings.json`

**인덱스 필드**

| 필드 | 타입 | 분석기 | 비고 |
|---|---|---|---|
| `id` | Keyword | — | stock.id (PK) |
| `symbol` | Keyword | — | 완전 일치 boost ×5 |
| `name` | Text | autocomplete (edge_ngram) + nori | boost ×2 |
| `market` | Keyword | — | KOSPI·KOSDAQ·NASDAQ·NYSE |
| `sector` | Text | nori | boost ×0.5 |
| `industry` | Text | nori | boost ×0.5 |
| `isActive` | Boolean | — | filter 필수 |

**API**
```
GET /api/stocks/search?query=삼성
```

**파이프라인**
```
StockIndexer(@PostConstruct) → stocks ES
Worker 시세수집 → stocks DB → dual-write → stocks ES
```

> ES 불가 시 PostgreSQL `ILIKE` 검색으로 자동 폴백

---

## 2. 뉴스 전문검색 — `news_articles`

**관련 파일**
- `api/news/infrastructure/NewsDocument.kt`
- `api/news/infrastructure/NewsSearchRepository.kt`
- `api/news/application/NewsIndexer.kt`
- `api/news/application/NewsSearchService.kt`
- `worker/news/NewsDocument.kt`
- `worker/news/NewsCollector.kt` (dual-write)
- `api/src/main/resources/elasticsearch/news-index-settings.json`

**인덱스 필드**

| 필드 | 타입 | 분석기 |
|---|---|---|
| `id` | Keyword | — |
| `stockId` | Long | — (filter) |
| `title` | Text | nori · boost ×3 |
| `description` | Text | nori · boost ×1 |
| `sentiment` | Keyword | — (filter) |
| `publishedAt` | Date (epoch_millis) | — (range filter) |
| `source` | Keyword | — |

**API**
```
GET /api/stocks/{stockId}/news?query=반도체&sentiment=POSITIVE&from=&to=
GET /api/news/search?query=금리인상&sentiment=NEGATIVE
```

**파이프라인**
```
NewsIndexer(@PostConstruct, 10,000건) → news_articles ES
NewsCollector(Worker 30분 주기) → news_articles DB → dual-write → news_articles ES
```

> ES 불가 시 DB `findByStockId()` 조회로 자동 폴백

---

## 3. 이벤트 타임라인 — `stock_events`

**관련 파일**
- `api/event/infrastructure/StockEventDocument.kt`
- `api/event/infrastructure/StockEventSearchRepository.kt`
- `api/event/application/EventIndexer.kt`
- `api/event/application/EventSearchService.kt`
- `api/event/api/EventTimelineController.kt`
- `worker/detector/StockEventDocument.kt`
- `worker/detector/StockEventWriter.kt` (dual-write)
- `api/src/main/resources/elasticsearch/event-index-settings.json`

**인덱스 필드**

| 필드 | 타입 | 분석기 |
|---|---|---|
| `id` | Keyword | — |
| `stockId` | Long | — (filter) |
| `eventType` | Keyword | — (filter) |
| `title` | Text | nori · boost ×3 |
| `description` | Text | nori · boost ×1 |
| `importanceScore` | Integer | — (range filter) |
| `sentimentScore` | Double | — |
| `eventTime` | Date (epoch_millis) | — (range filter) |
| `sourceType` | Keyword | — |

**API**
```
GET /api/stocks/{stockId}/events?query=어닝&types=DISCLOSURE_PUBLISHED&minScore=5&from=&to=
GET /api/events/search?query=금리인상&types=NEWS_PUBLISHED,DISCLOSURE_PUBLISHED
```

**파이프라인**
```
EventIndexer(@PostConstruct, 10,000건) → stock_events ES
StockEventWriter(Worker 탐지기) → stock_events DB → dual-write → stock_events ES
```

> `query` 없이 호출 시 기존 DB 조회 유지 — 기존 클라이언트 호환

---

## 4. 공시 검색 — `stock_events` (공유)

공시(`DISCLOSURE_PUBLISHED`)는 `stock_events` 인덱스를 공유하며 `eventType` 필터로 분리된다. 별도 인덱스 없음.

**관련 파일**
- `api/disclosure/api/DisclosureController.kt`
- `worker/disclosure/DisclosureCollector.kt` (dual-write)

**API**
```
GET /api/disclosures/search?query=유상증자&minScore=80
GET /api/stocks/{stockId}/disclosures?query=합병&from=&to=
```

**파이프라인**
```
DisclosureCollector(Worker 10분 주기) → stock_events DB → dual-write(rceptNo dedup) → stock_events ES
```

> `EventIndexer` @PostConstruct 동기화로 기존 공시 이력도 검색 가능

---

## 5. 스크리너 키워드 검색 — `stocks` (공유)

시세 데이터는 실시간이라 ES에 인덱싱하지 않는다. ES → 종목 ID 목록 → DB 시세 조회 파이프라인으로 구성.

**관련 파일**
- `api/screener/application/ScreenerService.kt`
- `api/screener/infrastructure/ScreenerRepository.kt` (`findItemsByStockIds()`)
- `api/screener/api/ScreenerController.kt`

**파이프라인**
```
query 입력
  → stocks ES (StockSearchService)
  → stockId 목록
  → DB 시세 조회 (candles_1m / candles_1d)
  → ScreenerResult
```

**API**
```
GET /api/screener                              ← 기존 시세 기반 정렬 (변경 없음)
GET /api/screener/search?query=반도체&sort=volume&limit=20
```

> sort: `amount`(거래대금) · `volume`(거래량) · `rise` · `fall`

---

## 6. AI 요약 캐싱 및 검색 — `stock_summaries`

ES를 캐시 저장소로 사용한다. Claude API 호출을 줄이고, 생성된 요약을 키워드로 탐색한다. DB 테이블 없이 ES만 저장소로 사용.

**관련 파일**
- `api/ai/SummaryDocument.kt`
- `api/ai/SummarySearchRepository.kt`
- `api/ai/StockSummaryService.kt`
- `api/ai/StockSummaryController.kt`
- `api/src/main/resources/elasticsearch/summary-index-settings.json`

**인덱스 필드**

| 필드 | 타입 | 비고 |
|---|---|---|
| `id` | Keyword | stockId 문자열 (upsert key) |
| `stockId` | Long | filter |
| `stockName` | Keyword | — |
| `summary` | Text (nori) | 검색 대상 |
| `generatedAt` | Date | TTL 판단 기준 (1시간) |

**캐시 흐름**
```
GET /api/stocks/{stockId}/summary
  → ES 캐시 확인 (generatedAt >= now-1h)
    ├─ HIT  → ES에서 즉시 반환
    └─ MISS → Claude API 호출 → ES 저장(upsert) → 반환
```

**API**
```
GET /api/stocks/{stockId}/summary          ← ES 캐시 자동 적용
GET /api/summaries/search?query=반도체&limit=10
```

---

## 7. 관심종목 검색 — `watchlist_items`

**관련 파일**
- `api/watchlist/infrastructure/WatchlistItemDocument.kt`
- `api/watchlist/infrastructure/WatchlistSearchRepository.kt`
- `api/watchlist/application/WatchlistIndexer.kt`
- `api/watchlist/application/WatchlistService.kt`
- `api/watchlist/api/WatchlistController.kt`
- `api/src/main/resources/elasticsearch/watchlist-index-settings.json`

**인덱스 필드**

| 필드 | 타입 | 가중치 |
|---|---|---|
| `userId` | Long | filter 필수 |
| `groupId` / `groupName` | Long / Keyword | — |
| `stockId` / `symbol` | Long / Keyword | symbol 완전일치 ×5 |
| `stockName` | Text (nori) | boost ×3 |
| `sector` | Text (nori) | boost ×1 |
| `memo` | Text (nori) | boost ×2 |
| `targetPrice` | Double | — |

**API**
```
GET /api/watchlists/search?query=반도체&limit=20
```

**파이프라인**
```
WatchlistIndexer(@PostConstruct, 전체) → watchlist_items ES
addItem()    → DB 저장 → ES 인덱싱
removeItem() → DB 삭제 → ES 삭제
```

> `userId` 필터 필수 — 타 사용자 데이터 노출 없음

---

## 8. 알림 이력 검색 — `alert_histories`

**관련 파일**
- `api/alert/infrastructure/AlertHistoryDocument.kt`
- `api/alert/infrastructure/AlertHistorySearchRepository.kt`
- `api/alert/application/AlertHistoryIndexer.kt`
- `api/alert/application/AlertService.kt` (`searchHistory()`)
- `api/alert/api/AlertController.kt`
- `worker/alert/AlertHistoryDocument.kt`
- `worker/alert/AlertEvaluator.kt` (dual-write)
- `api/src/main/resources/elasticsearch/alert-index-settings.json`

**인덱스 필드**

| 필드 | 타입 | 비고 |
|---|---|---|
| `userId` | Long | filter 필수 |
| `ruleId` | Long | — |
| `stockId` | Long | filter |
| `ruleType` | Keyword | PRICE_ABOVE·PRICE_BELOW·VOLUME_SURGE 등 |
| `message` | Text (nori) | 검색 대상 ("가격이 ₩75,000 이상이 되었습니다") |
| `deliveryStatus` | Keyword | PENDING·SENT·FAILED |
| `triggeredAt` | Date (epoch_millis) | range filter |

**API**
```
GET /api/alerts/history/search?query=목표가&stockId=1&ruleType=PRICE_ABOVE&deliveryStatus=SENT&from=&to=
```

**파이프라인**
```
AlertHistoryIndexer(@PostConstruct, 50,000건) → alert_histories ES
AlertEvaluator(Worker 틱 이벤트) → DB INSERT + UPDATE → dual-write → alert_histories ES
```

> ES 실패는 WARN 로그만 — 알림 발송 트랜잭션에 영향 없음
