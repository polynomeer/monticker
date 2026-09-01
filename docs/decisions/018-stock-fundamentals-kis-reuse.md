# ADR-018: 시가총액·PER·PBR 스크리너 필터 — KIS 응답 필드 재사용 + 스냅샷 테이블

## Status
Accepted

## Context

`docs/ui-benchmarks.md`가 Finviz를 벤치마킹하며 채택하기로 한 핵심 원칙은 "필터 ≠ 표시 컬럼 분리"였다. 그런데 스크리너(`ScreenerItem`)엔 price/changeRate/volume/amount/buyRatio/sector만 있고 시가총액·PER·PBR 같은 펀더멘털 필드가 아예 없어서, 나눌 "표시 컬럼" 자체가 존재하지 않았다 (`docs/ui-benchmarks.md:89`, "착수 보류 — 펀더멘털 데이터 소스 결정 필요").

후보:

**A) 신규 벤더 도입** (DART 재무정보, 별도 시세 API 등)
- DART `재무정보` 엔드포인트는 매출·영업이익 등 원본 재무제표는 주지만, 시가총액·PER·PBR은 (주가 × 발행주식수) 계산이 필요해 직접 제공하지 않는다.

**B) 이미 호출 중인 KIS `inquire-price` 응답 필드 재사용**
- `KisClient.fetchPriceInternal`(tr_id `FHKST01010100`)은 이미 1초 주기로 종목별 시세를 조회 중이고, 이 응답엔 `per`, `pbr`, `eps`, `bps`, `hts_avls`(시가총액) 필드가 포함돼 있는데 지금은 파싱하지 않고 버리고 있었다.

## Decision

**B안 — 신규 벤더 없이 KIS `inquire-price` 응답에서 필드만 더 파싱**한다.

파이프라인:
```
KisClient.fetchPrice(symbol) — per/pbr/eps/bps/marketCap 필드 추가 파싱
  → StockFundamentalsCollector (worker, 매일 16:30 배치)
  → stock_fundamentals 테이블 (stock_id PRIMARY KEY, 종목당 1행)
  → ScreenerRepository (LEFT JOIN, api)
  → GET /api/screener?marketCapTier=large|mid|small
  → 스크리너 시가총액 필터 + "밸류에이션" 표시 컬럼셋
```

- **스키마 — 시계열이 아닌 스냅샷**: [ADR-017](017-investor-flow-kis-integration.md)의 `investor_flow`는 종목 상세 페이지에서 "최근 N일 히스토리"를 보여주는 시계열이라 `(stock_id, trade_date)` 테이블이 맞았다. 이번엔 다르다 — 스크리너가 ~200개 종목 전체를 대상으로 필터/정렬하는 쿼리라, 시계열 테이블이면 `candles_1m`처럼 종목마다 LATERAL 서브쿼리가 필요해진다. `stock_fundamentals`는 종목당 1행(`stock_id PRIMARY KEY`)으로 설계해 단순 `LEFT JOIN`으로 끝나게 했다 — "현재 값"만 필요하다는 의미에도 더 맞다.
- **수집 방식**: `InvestorTrendCollector`와 동일하게 일일 배치(`@Scheduled(cron = "0 30 16 * * MON-FRI")`, 16:00 InvestorTrendCollector 다음 슬롯) + `@DistributedLock`. 부트스트랩용 `collectOnStartup()`도 추가했다 — `investor_flow`는 비어 있어도 상세 패널 하나만 빈 상태로 보이지만, `stock_fundamentals`가 비면 스크리너 시가총액 필터 자체가 배포 당일 종일 빈 결과만 반환해 훨씬 눈에 띄게 망가진다.
- **적용 범위**: KOSPI/KOSDAQ 종목만 (KIS 국내 API). 해외 종목은 `stock_fundamentals` 행이 없어 시총 필터가 항상 빈 결과가 되므로, 프론트에서 `market=overseas` 선택 시 시총 필터 UI 자체를 숨긴다.
- **Mock 폴백**: KIS 미설정/무응답 시 `is_mocked` 플래그로 표시하고 결정적 의사난수로 채운다 — `investor_flow`와 동일한 패턴. 다만 대형/중형/소형 세 구간에 고르게 분산되도록 `stockId % 3` 기반으로 구간을 고정해, KIS 미설정 상태(로컬 개발)에서도 시총 필터 세 구간이 전부 테스트 가능하게 했다.
- **스코프**: 시가총액 구간 필터 1개 + 밸류에이션 컬럼셋(시가총액/PER/PBR) 1개로 한정한다. `ui-benchmarks.md:87`이 이미 "Finviz의 5×5 그리드 전면 도입보다 필터 축 2~3개만 점진적으로"라고 스코프를 줄여놨다. 배당수익률·매출/영업이익 성장률은 이 응답에 없어 스코프 밖.

## Reasons

- 새 벤더 계약이나 새 엔드포인트 호출조차 필요 없다 — 이미 매초 호출 중인 응답에서 버려지던 필드를 파싱만 하면 된다.
- DART 재무정보로는 시가총액·PER·PBR을 직접 얻을 수 없다 (파생 계산이 추가로 필요) — KIS가 이미 계산된 값을 준다.
- 스크리너는 전체 종목을 훑는 쿼리라 시계열보다 스냅샷이 성능·의미 둘 다에서 맞다.
- **기존 침묵 목업과의 관계**: `ScreenerRepository`의 `buyRatio`(매수/매도 비율)는 이미 완전한 목업이지만 어디에도 `isMocked` 표시가 없다 (`buyRatio = (40 + (stockId * 7 + volume) % 31)`). 이번 `isFundamentalsMocked`는 스크리너 페이지에 처음 등장하는 "모의 데이터" 명시적 노출이다 — 기존 목업과 다르게 취급하는 게 아니라, `investor_flow`/ADR-017 이후 확립된 "금융 데이터는 목업이어도 정직하게 표시한다" 원칙을 신규 필드에 일관 적용한 것.

## Consequences

- `hts_avls`(시가총액) 필드의 KIS 단위 관례(억원 추정)를 실제 운영 키로 검증하지 못했다 — ADR-017과 같은 미검증 리스크. 실전 배포 전 KIS 응답을 실제로 한 번 확인하고 `StockFundamentalsCollector`의 `* 100_000_000L` 변환이 맞는지 재확인할 것.
- 해외 종목은 이 필터가 항상 비어 있다.
- 배당수익률·재무 성장률 필터는 이번 스코프에 없다.
- 하루 단위 스냅샷이라 장중 변동(주가가 바뀌어도 PER/PBR이 그날 수집 시점 기준으로 고정)을 반영하지 않는다 — 스크리너 필터 용도로는 이 정도 정확도면 충분하다고 판단.
- 일일 배치가 InvestorTrendCollector(16:00)에 이어 하나 더(16:30) 늘었다 — 둘 다 KOSPI/KOSDAQ 전체 종목을 순회하며 KIS를 개별 호출하는 동일한 모양의 루프.

## Revisit When

- 배당수익률/성장률 필터 수요가 생기면 → DART 재무정보 엔드포인트 또는 별도 KIS 엔드포인트 연동, 신규 ADR.
- 일일 배치가 세 번째로 늘어나게 되면 → 종목별 KIS 호출을 배치마다 따로 순회하지 말고 한 번의 순회로 합치는 것을 고려 (현재는 두 개까지는 허용 범위로 판단).
- `hts_avls` 단위 검증 결과 가정이 틀렸던 것으로 확인되면 → 변환 로직 수정 + 기존 저장된 `market_cap` 값 재수집.
