# ADR-017: 개인·외국인·기관 순매수(투자자 동향) 데이터 — KIS API 확장

## Status
Accepted

## Context

`docs/ui-benchmarks.md`에서 토스증권을 벤치마킹하며 "개인·외국인·기관 순매수" 위젯을 리스크/Analytics 후보로 남겼으나, monticker 도메인에는 이 데이터가 전혀 없었다 — 가격·거래량·이벤트·뉴스와는 완전히 다른 축(KRX 투자자별 매매동향)이라 목업으로 대체할 수 없었다.

진행하려면 먼저 데이터 소스를 정해야 했다. 후보:

**A) 신규 벤더 도입** (KRX 정보데이터시스템 Open API 등)
- 새 계약/API 키 발급, 새 인증 방식, 새 클라이언트 아키텍처가 필요.

**B) 기존 KIS(한국투자증권) Open API 확장**
- monticker는 이미 KIS를 실시간 시세·호가·주문 체결에 사용 중이다 (`backend/worker/.../kis/KisClient.kt`, `backend/api/.../brokerage/infrastructure/KisBrokerageClient.kt`).
- KIS Open API는 `/uapi/domestic-stock/v1/quotations/inquire-investor` (tr_id `FHKST01010900`)로 종목별 최근 30영업일의 개인/외국인/기관계 순매수 수량·대금을 제공한다.
- 기존 `KisClient`의 토큰 발급·캐싱·서킷브레이커 인프라를 그대로 재사용할 수 있다.

## Decision

**B안 — 기존 KIS 클라이언트에 `fetchInvestorTrend` 엔드포인트를 추가**한다. 새 벤더를 도입하지 않는다.

파이프라인:
```
KisClient.fetchInvestorTrend(symbol)
  → InvestorTrendCollector (worker, 매일 장마감 후 배치)
  → investor_flow 테이블 (stock_id, trade_date UNIQUE)
  → InvestorFlowService (api)
  → GET /api/stocks/{stockId}/investor-flow?days=20
  → InvestorFlowPanel (종목 상세 페이지)
```

- **수집 방식**: 실시간이 아니라 **일일 배치**(장마감 후 1회) — 이 데이터 자체가 KIS에서도 영업일 단위로만 제공되는 EOD 성격이라 `MarketDataCollector`(1초 주기 틱)가 아니라 `StockMasterCollector`/`DisclosureCollector`와 같은 `@Scheduled` 일일 배치 패턴을 따른다.
- **적용 범위**: KOSPI/KOSDAQ 종목만 (KRX 데이터이므로 NASDAQ/NYSE 종목은 대상 아님).
- **Mock 폴백**: `KisClient.isConfigured`가 false거나 응답이 비어있으면, `StockMasterCollector`가 이미 쓰는 것과 동일한 "컬렉터 레벨 폴백" 패턴으로 결정적 의사난수 mock 데이터를 생성한다 (ADR-015의 `@ConditionalOnProperty` 빈 분리 패턴은 PG/Brokerage처럼 "실제 자금이 오가는" 클라이언트에 쓰는 무거운 패턴이라, 순수 조회성 시세 데이터인 이 기능엔 과함 — 기존 `KisClient`가 이미 쓰는 가벼운 `isConfigured` 폴백 패턴을 따른다).
- **배치 위치**: 종목 상세 페이지 (OrderBook·EventTimeline과 같은 층위). Analytics/리스크는 "내 포트폴리오"에 대한 분석이고, 이 데이터는 "이 종목 자체"에 대한 시장 데이터라 종목 상세가 더 맞다 — ui-benchmarks.md 작성 당시의 "리스크/Analytics" 제안을 재검토해 변경.

## Reasons

- 이미 발급받은 KIS API 키·인증 인프라를 재사용 — 새 벤더 계약·비용·인증 방식 결정이 필요 없다.
- `KisClient`/`StockMasterCollector`의 기존 코드 패턴을 그대로 복제하므로 새 아키텍처 개념이 늘지 않는다.
- 일일 배치는 이 데이터의 실제 갱신 주기(영업일 단위)와 정확히 맞는다 — 더 잦은 폴링은 낭비.

## Consequences

- KIS Open API의 `inquire-investor` 필드 스펙(`prsn_ntby_qty` 등)은 KIS 공식 문서 기준으로 매핑했으나, 실제 운영 키로 첫 검증이 필요하다 — 실전 배포 전 KIS 개발자센터에서 응답 스키마를 재확인할 것.
- KOSPI/KOSDAQ 외 종목은 이 위젯이 항상 비어 보인다 — 프론트에서 명시적으로 "국내 종목만 제공" 안내 필요.
- 신규 테이블(`investor_flow`) 하나가 늘어난다 — 종목 수 × 영업일 수로 선형 증가, 오래된 데이터 보존 정책은 추후 결정.

## Revisit When

- KIS API 요청 한도(rate limit)에 걸리기 시작하면 → 종목별 배치를 분산하거나 우선순위(관심종목 우선) 큐로 전환.
- 해외 종목 수급 데이터 수요가 생기면 → 별도 벤더(예: 미국은 13F 기반 기관 보유 데이터) 검토, 이 ADR과는 별개로 신규 ADR 작성.
