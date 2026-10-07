# ADR-087: 이벤트·섹터 집계는 조회 시점 SQL + 짧은 Redis 캐시로 계산하고, 섹터 등락률은 동일가중 평균으로 낸다

## Status
Accepted

## Context

디자인 시안 적용([ADR-066](066-terminal-ui-shell.md)) 뒤 세 곳이 집계 API 없이 비어 있거나 대용 값을 보여줬다
([design-rollout-plan.md](../design-rollout-plan.md) P2).

- 홈 상단 "오늘 이벤트"·"급등·급락" — `—`. `/api/events/recent`는 최대 50건이라 하루 건수를 셀 수 없다.
- /compare "이벤트 수" — 종목마다 이벤트를 100건까지 받아 개수를 세고, 넘으면 "100+".
- 홈 섹터 히트맵 — 등락률 대신 최근 24시간 섹터별 이벤트 수로 색 진하기를 대신했다. 게다가 그 API(`/api/sectors/events`)는
  로그인이 필요해 비로그인 홈에서는 항상 비어 있었다.

세 가지 모두 핫 테이블(`stock_events`, `candles_1m`/`candles_1d` hypertable)을 읽는다. 고려한 대안:

1. **조회 시점 SQL + 짧은 TTL Redis 캐시** — 채택.
2. **물리 집계 테이블/Continuous Aggregate**(worker가 이벤트 저장 시 일별 카운터를 올리거나 TimescaleDB CAGG) — 이벤트 쓰기 경로
   (worker `EventDetector`)와 두 모듈에 걸친 정합성(재처리·dedup 실패 시 카운터 보정)을 새로 떠안는다. `stock_events`는 hypertable이 아니라
   CAGG도 바로 쓸 수 없다.
3. **클라이언트에서 기존 API를 여러 번 호출해 합산** — 지금의 "100+"와 같은 상한 문제를 못 푼다.

섹터 등락률 가중 방식도 골라야 했다.

- **시가총액 가중** — 지수와 비슷한 감각이지만 `stock_fundamentals.market_cap`이 비어 있거나 개발용 모의값(`is_mocked`)인 종목이 있다.
  가중치에 모의값이 섞이면 지어낸 숫자가 섹터 색을 좌우한다(원칙: 지어낸 숫자를 실데이터처럼 보여주지 않는다).
- **동일가중(종목 단순 평균)** — 채택.

## Decision

### API

| 엔드포인트 | 응답 | 경계·상한 | 캐시 |
|---|---|---|---|
| `GET /api/events/summary?date=YYYY-MM-DD` (생략 시 오늘) | `{date, from, to, total, byType:[{eventType,count,stockCount}], surgeStocks, plungeStocks}` | KST 하루 `[자정, 다음 자정)`. 미래·366일보다 먼 과거는 400 | `event-summary` 30초 |
| `GET /api/events/counts?stockIds=1,2&days=180` | `{from, to, days, counts:[{stockId,count}]}` — 요청 종목 전부(0건 포함) | `[KST(오늘−days) 자정, 내일 KST 자정)`. 종목 1~20개, days 1~1095. 잘못된 ID는 400 | `event-counts` 60초(키 = 정렬한 ids·days·오늘) |
| `GET /api/screener/sectors/performance?market=all\|domestic\|overseas` | `{market, weighting:"EQUAL", eventsSince, sectors:[{sector, stockCount, pricedCount, avgChangeRate, advancers, decliners, unchanged, eventCount}], updatedAt}` | 종목 수 많은 순 최대 100섹터 | `sector-performance` 30초(키 = market) |

- 셋 다 비로그인 공개(기존 `/api/events/**`, `/api/screener/**` GET 규칙).
- `surgeStocks`/`plungeStocks`는 그날 `PRICE_SPIKE`/`PRICE_DROP` 이벤트가 난 **종목 수**다. 같은 종목이 하루 여러 번 급등 이벤트를 내도 한 번 센다(건수는 `byType`).
- `byType`은 모든 `EventType`을 0건이라도 채운다. enum에 없는 유형이 DB에 있으면 버리지 않고 뒤에 붙인다.

### 하루의 경계

KST 날짜를 `LocalDate.atStartOfDay(Asia/Seoul)`로 Instant로 바꾸고, SQL에는 `event_time >= ? AND event_time < ?`
(timestamptz끼리, `OffsetDateTime` UTC 바인딩)로만 넘긴다. 컬럼에 `AT TIME ZONE`을 씌우지 않는다 — 세션 타임존에 따라 결과가 바뀌어
CI에서만 깨진 전례가 있다. 통합 테스트는 KST 자정 직전·직후 이벤트로 경계를 고정하고, `-Duser.timezone=UTC`로도 돌린다.

### 섹터 등락률

종목 등락률은 **스크리너 목록과 같은 식**(`ScreenerRepository.buildBase`의 `change_pct` = 최신 1분봉 종가 vs 직전 일봉 종가)을 그대로 재사용한다.
섹터 값 = 등락률을 계산할 수 있는 종목(`change_pct IS NOT NULL`)의 단순 평균. 시세가 하나도 없으면 `avgChangeRate = null`이고
화면은 색 없이 `—`를 그린다(0%와 구분). `eventCount`는 오늘(KST) 그 섹터 활성 종목의 이벤트 수로, 히트맵의 보조 정보다.

### 인덱스

`V84__stock_events_time_type_index.sql` — `stock_events (event_time, event_type, stock_id)`. 기존 인덱스(V5)는 모두 `stock_id`·`event_type`·
`importance_score`가 선두라 "하루 전 종목 유형별 집계"가 테이블 전체를 훑었다. 종목별 건수는 기존 `(stock_id, event_time DESC)`를 쓴다.
캔들 쪽은 기존 `(stock_id, candle_time DESC)` 인덱스로 LATERAL 1행 조회를 한다(스크리너와 같은 비용).

## Reasons

- 쓰기 경로를 건드리지 않는다. 집계 결과가 원본과 어긋날 여지가 없다(재처리·dedup 실패를 카운터에 반영할 필요가 없다).
- 홈은 모든 방문자가 같은 값을 본다 — 키가 날짜/시장 하나뿐이라 30초 캐시면 SQL은 인스턴스당 30초에 한 번이다.
- 등락률 식을 한곳(`buildBase`)에 두어 스크리너 "등락률" 열과 히트맵이 다른 값을 내지 않는다.
- 동일가중은 모의 시가총액이 섹터 값에 끼어들 길을 막는다.

## Consequences

- 섹터 집계는 활성 종목 전부에 LATERAL 두 번(1분봉 최신, 일봉 직전)을 돈다. 종목 수가 크게 늘면(해외 전 종목 등) 30초마다 한 번의 비용도 커진다.
- 동일가중이라 소형주 몇 개가 크게 움직이면 섹터 색이 대형주 체감과 다를 수 있다. 화면 하단에 "단순 평균"이라고 적는다.
- 스크리너 식을 그대로 쓰므로 그 한계도 같다: 직전 일봉은 "최신 일봉의 바로 앞"이라, 당일 일봉이 아직 없으면 이틀 전 종가와 비교한다.
  거래가 멈춘 종목의 오래된 1분봉도 그대로 들어간다.
- /compare 이벤트 점(겹침 차트)과 "이벤트 후 1일 평균"은 여전히 종목당 최근 100건 목록으로 계산한다. 건수만 정확해졌고, 100건을 넘으면 화면에 그렇게 적는다.
- 캐시 TTL만큼(최대 30~60초) 값이 늦다.

## Revisit When

- 섹터 집계 p95가 스크리너 목록 수준을 넘거나 활성 종목이 1만 개를 넘을 때 — 섹터별 스냅샷을 worker가 주기적으로 만들어 두는 방식으로 바꾼다.
- `stock_fundamentals.market_cap`이 실데이터로 채워지고 모의값이 없어지면 — 시가총액 가중을 옵션으로 추가한다.
- `stock_events`가 hypertable이 되면 — 일별 Continuous Aggregate로 `summary`를 옮긴다.
