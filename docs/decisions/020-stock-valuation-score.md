# ADR-020: 종목 스코어(Snowflake 참고) v1 — 밸류에이션 1축만 실데이터

## Status
Accepted

## Context

`docs/ui-benchmarks.md`가 Simply Wall St의 "Snowflake"(밸류에이션/성장성/과거실적/재무건전성/배당 5축 오각형 시각화)를 Analytics 페이지 개선 후보로 남겨뒀고, 사용자가 착수를 요청했다.

착수 전 두 가지가 확인됐다:

1. **배치 위치**: `/analytics` 페이지는 포트폴리오 최적화·세금·Kelly·패턴·국면 탐지를 다루는 "Quant Analytics"다. Snowflake는 종목 하나의 시장 데이터 요약이라 이 포트폴리오 레벨 페이지와 성격이 다르다 — [ADR-017](017-investor-flow-kis-integration.md)이 정확히 같은 이유로 "리스크/Analytics" 후보였던 투자자 동향을 종목 상세 페이지로 옮긴 전례와 동일.
2. **데이터 가용성**: 5축 중 실데이터로 계산 가능한 건 밸류에이션(PER, `stock_fundamentals` — [ADR-018](018-stock-fundamentals-kis-reuse.md)) 하나뿐이다.
   - 성장성/재무건전성/배당: 스키마 전체에 관련 필드가 아예 없음 (매출/영업이익 성장률, 부채비율, 배당수익률 등 — DART 재무정보 또는 새 KIS 엔드포인트 연동이 필요, 별도 작업).
   - 과거실적: `candles_1d` 테이블에서 트레일링 수익률을 계산하려 했으나, **`candles_1d`를 채우는 코드가 백엔드 전체에 없다**는 걸 확인했다 (`CandleAggregator.kt`는 `candles_1m`에만 쓰고, `candles_1d_cagg`는 TimescaleDB 전용 별개 뷰). 이는 스크리너의 기존 등락률/급상승/급하락 정렬에도 영향을 주는 pre-existing 버그라 이번 작업과 분리해 별도 이슈로 플래그했다.

## Decision

**v1은 밸류에이션 1축만 실데이터로 제공하고, 나머지 4축은 "데이터 준비중"으로 명시한다. 5축 오각형(pentagon) 시각화는 v1에서 만들지 않는다.**

- **모듈**: `com.monticker.api.stockscore` — `investor` 모듈(ADR-017)과 동일한 패턴. `package-info.java` 없는 암묵적 모듈, `JdbcTemplate` 원시 SQL로 `stock_fundamentals`를 직접 읽는다. 스크리너/matching/paper/backtest 모듈이 전부 `candles_*` 테이블을 이렇게 읽는 것과 같은, [ADR-019](019-spring-modulith-boundary-conventions.md) 정리 이후에도 유지되는 코드베이스의 정착된 패턴.
- **밸류에이션 스코어링**: `stock_fundamentals`에서 `per > 0`인 전체 종목 모집단 대비 대상 종목의 PER 백분위(`PERCENT_RANK()`)를 계산, 3분위로 0/1/2점 변환 — 저PER(하위 1/3) → 2점(저평가), 고PER(상위 1/3) → 0점(고평가). 모집단 중 `is_mocked` 비율이 과반이면 `isValuationPopulationMocked`를 응답에 실어 스코어 신뢰도를 명시한다.
- **오각형을 안 만든 이유**: ECharts `radar` 시리즈는 축마다 값 하나를 이어 하나의 도형을 그린다. 4축이 "데이터 없음"인 상태에서 baseline 값(예: 0.3)을 채워 도형을 완성하면, 캡션을 달아도 "이 종목이 성장성/재무건전성/배당에서 낮은 점수를 받았다"는 틀린 신호를 도형 자체가 시각적으로 주장하게 된다. `investor_flow`의 "모의 데이터" 배지(합성이지만 그럴듯한 값)와는 성격이 다르다 — 이번엔 추정조차 아닌 값이다. 실데이터 축이 1개뿐이면 선분/도형 자체가 의미 있게 그려지지도 않는다.

## Reasons

- 신규 벤더·신규 엔드포인트 없이 이미 있는 `stock_fundamentals`(ADR-018)만으로 v1을 낼 수 있다.
- `investor` 모듈 패턴을 그대로 재사용해 새 아키텍처 개념을 늘리지 않는다.
- 데이터가 없는 축을 시각적으로 있는 것처럼 꾸미지 않는다 — 이 세션에서 확립된 "금융 데이터는 목업이어도 정직하게 표시한다" 원칙(ADR-017/018)을 오각형이라는 새로운 시각화 형태에도 일관 적용.

## Consequences

- 해외 종목(NASDAQ/NYSE)은 `stock_fundamentals` 행 자체가 없어 밸류에이션도 unavailable.
- KIS 미설정 환경(로컬 개발 기본값)에서는 비교 모집단 자체가 사실상 전부 목업이라, 스코어가 계산은 되지만 `isValuationPopulationMocked=true`로 신뢰도가 낮음이 항상 표시된다.
- 5축 중 4축이 "준비중" 상태로 노출되므로, 기능이 완성돼 보이지 않을 수 있다 — 의도적인 선택.
- `candles_1d` 미채움 문제는 이 기능과 무관하게 스크리너 정렬에도 영향을 주는 별도 버그로, 이 ADR의 스코프 밖에서 별도 추적.

## Revisit When

- `candles_1d`가 채워지면(별도 이슈) → 과거실적 축 추가.
- DART 재무정보 또는 새 KIS 엔드포인트가 연동되면 → 성장성·재무건전성·배당 축 추가.
- 축이 2~3개 이상 실데이터로 채워지면 → 오각형 시각화 재검토.
