# UI 벤치마크

monticker의 화면 설계를 개선할 때 참고하기 위해 조사한 외부 서비스 모음이다. 각 서비스에서 무엇이 좋았는지, monticker의 어떤 화면에 어떻게 적용할지, 그리고 **의도적으로 채택하지 않은 것**까지 정리한다. 새 화면을 설계하거나 기존 화면을 리디자인할 때 이 문서를 먼저 확인한다.

이 문서는 결정(ADR)이 아니라 **재료 모음**이다. 여기서 실제로 특정 패턴을 채택하기로 결정하면(예: 화면 구조를 코드 편집기 기반으로 바꾼다 같은 큰 결정), 별도 ADR을 `docs/decisions/`에 작성한다.

## 조사 대상

| 서비스 | 카테고리 | URL |
|---|---|---|
| qfex | 파생상품(레버리지) 트레이딩 터미널 | qfex.com/trade/US100-USD |
| Hyperliquid | 파생상품 트레이딩 터미널 (qfex류의 원조) | app.hyperliquid.xyz |
| Binance Futures | 파생상품 트레이딩 터미널 (리테일 대중형) | binance.com/en/futures |
| 토스증권 | 한국 주식 앱 (위젯 대시보드) | tossinvest.com |
| Finviz | 미국 주식 스크리너 | finviz.com/screener.ashx |
| TradingView | 차트 · 전략 스크립팅 · 소셜 | tradingview.com |
| Robinhood | 미국 주식 앱 (마케팅 페이지) | robinhood.com |

---

## 서비스별 벤치마킹

### qfex / Hyperliquid — 퍼프 트레이딩 터미널

qfex는 Hyperliquid의 레이아웃을 그대로 따르는 클론에 가깝다. 둘 다 고정 3~4분할 구조:

```
상단 티커 바 (심볼, Mark Price, 변동률, 거래량, 펀딩비/미결제약정)
┌─────────────┬──────────────┬─────────────┐
│             │  오더북/체결   │  주문 패널    │
│    차트      │  (Price/Size/ │  (Market/    │
│             │   Cumulative) │   Limit)     │
├─────────────┴──────────────┴─────────────┤
│  Positions / Orders / History (탭 통합)     │
└───────────────────────────────────────────┘
```

**채택할 만한 것**
- 오더북 잔량을 셀 배경 바(heatmap)로 시각화 — 숫자만 나열하는 것보다 잔량 크기가 즉시 보임
- 매수/매도 버튼을 대각선 스플릿 하나로 합친 디자인 (버튼 2개 대신 1개, 클릭 위치로 방향 결정)
- 계산된 값(수수료, 예상 포지션 등)을 "회색 라벨 — 흰 값" 행으로 나열하는 패턴 — 입력값과 시스템이 계산한 값을 시각적으로 구분
- **Positions/Open Orders/Order History/Trade History를 별도 카드가 아니라 탭 하나로 통합** — 화면 공간을 크게 절약
- qfex의 심볼 검색 모달: 카테고리 필터 탭(All/관심종목/Equities/...) + 종목당 미니 스파크라인 + 1D 변동률 + 즐겨찾기 별표를 한 줄에

**적합하지 않은 것**
- Cross/레버리지 배지, Reduce Only, 청산가(Liquidation Price) 같은 레버리지 파생상품 전용 개념 — monticker는 모의투자(현물)이므로 그대로 가져올 개념 자체가 없음

### Binance Futures — 더 밀도 높은 버전

Hyperliquid류와 같은 골격이지만 정보 밀도가 훨씬 높다: 차트에 이동평균선·거래량 오실레이터가 기본 오버레이되어 있고, 상단에 여러 종목의 실시간 변동률이 흐르는 워치리스트 스트립이 있다.

**채택할 만한 것**
- 상단 워치리스트 스트립(관심종목 등락률을 한 줄로 계속 흘려보내기) — 홈 화면 헤더에 넣으면 좋을 패턴
- 주문 패널의 "BBO"(최우선 호가로 즉시 채우기) 버튼처럼, 가격 입력 옆에 원클릭 단축 버튼을 붙이는 방식

**적합하지 않은 것**
- 화면 전체가 하나의 거대한 밀도 — monticker는 이미 Dracula 팔레트로 여백을 확보한 방향이라, 이 정도 밀도는 번잡스러워 보일 위험. 부분적으로만 차용.

### 토스증권 — 위젯 대시보드 + 한국 시장 데이터

가장 다른 설계 철학. 고정 3분할이 아니라 **카드형 위젯 그리드**이고, 각 위젯에 `×`(제거)/`+`(추가) 컨트롤이 있어 사용자가 대시보드를 직접 편집한다("레벨 편집").

**채택할 만한 것**
- **로그인 게이트 패턴**: 호가창처럼 로그인이 필요한 위젯은 깨진 화면 대신 위젯 내부에 "호가를 보려면 로그인이 필요해요" + CTA 버튼만 표시 — 페이지 전체를 막지 않고 위젯 단위로 처리
- **개인·외국인·기관 순매수 위젯**: 수평 바 차트 + 일별 테이블 — 한국 시장 특유의 수급 데이터라 crypto 레퍼런스엔 없던 것. [리스크](apps/web/src/app/risk/page.tsx)나 [Analytics](apps/web/src/app/analytics/page.tsx) 페이지에 참고
- 주문 패널의 수량 퀵버튼(10%/25%) — [TradeModal](apps/web/src/components/paper/TradeModal.tsx)에 없는 디테일
- 종목 상세 페이지에 커뮤니티(소셜) 피드가 그대로 붙어있는 구조 — monticker의 "이벤트 중심" 포지셔닝과 결이 비슷한 지점. 댓글/피드 자체를 만들자는 게 아니라, **뉴스·공시·이벤트가 종목 페이지의 1급 시민으로 붙어있어야 한다**는 원칙의 참고 사례

**적합하지 않은 것**
- 위젯 드래그앤드롭 커스터마이징 자체는 구현 비용 대비 monticker 현재 단계에 과함 — 언젠가 고려할 수는 있지만 지금 우선순위 아님

### Finviz — 다차원 스크리너

monticker [스크리너](apps/web/src/app/page.tsx)는 지금 pill 토글(전체/국내/해외, 거래대금순/거래량순/급상승/급하락) 위주다. Finviz는 완전히 다른 밀도:

- **필터 카테고리 탭**(Descriptive/Fundamental/News/ETF) 아래 **5×5 드롭다운 그리드** — Exchange, Market Cap, Earnings Date, Price, Theme 등 수십 개 조건을 동시에 조합
- **결과 컬럼셋 탭**(Overview/Valuation/Financial/Ownership/Performance/Technical...) — 필터 조건과 "표시할 컬럼"을 분리한 설계가 핵심. 같은 필터 결과를 다른 관점(밸류에이션 관점, 기술적 지표 관점)으로 바로 전환
- 결과를 "Save as Portfolio" / "Create Alert"로 바로 연결하는 동선

**채택할 만한 것**
- **필터 ≠ 표시 컬럼 분리** 원칙은 monticker 스크리너에 그대로 적용 가능 — 지금은 정렬 기준이 바뀌면 컬럼도 같이 바뀌는데, "무엇으로 거를지"와 "무엇을 볼지"를 나누면 훨씬 유연해짐
- 스크리닝 결과에서 바로 "관심종목 추가" / "알림 만들기"로 이어지는 동선

**적합하지 않은 것**
- 5×5 그리드 수준의 다차원 필터(Analyst Recom, Short Float, IPO Date 등)는 미국 주식 펀더멘털 데이터 의존도가 높음. monticker의 실시간성·이벤트 중심 포지셔닝과는 다른 축이라 전면 도입보다는 **점진적으로 필터 축 2~3개만 추가**하는 쪽이 맞음

### TradingView — 차트 · 전략 스크립팅 · 소셜

- 차트에 **매수/매도 가격 티켓이 현재가 축에 떠 있는** 패턴(SELL 317.85 / BUY 317.89, 스프레드 표시) — 차트를 보다가 바로 주문할 수 있는 동선
- **Pine Editor**: 코드로 전략을 짜는 스크립팅 IDE. monticker의 [Quant Lab 빌더](apps/web/src/app/quant-lab/builder/page.tsx)는 반대로 **코드 없이 조건 블록을 조합**하는 방식 — 이건 참고해서 바꿀 게 아니라, **지금 방식이 TradingView 대비 진입장벽이 낮다는 확인**으로 읽는 게 맞다. [quant-lab-positioning.md](domain/quant-lab-positioning.md)의 논조와도 일치.
- Products 메뉴에서 Screener/Portfolio/Calendar/Options를 아이콘 하나로 빠르게 전환 — 사이드바보다 가벼운 전환 UX

**채택할 만한 것**
- 차트 위에 뜨는 현재가 기준 매수/매도 퀵 버튼 — [StockChart](apps/web/src/components/stock/chart/StockChart.tsx) 또는 종목 상세 페이지에서 참고할 만함

**적합하지 않은 것**
- Pine Editor류 코드 에디터 — 위에서 설명한 이유로 명시적으로 배제

### Robinhood — 미니멀 + AI 자연어 요약

로그인 없이 보이는 공개 마케팅 페이지 기준. 화이트 배경에 여백을 극단적으로 확보하고, 가격은 큰 숫자 하나로:

- **"Stock Snapshot"**: 오늘 가격 움직임을 자연어 문단으로 요약("AAPL은 $318.01에 거래되며 시가총액 4.64T... 장중 저점 대비 +0.4%, 고점 대비 -1.0%..."). 표·차트가 아니라 한 문단짜리 서술.
- Key Statistics 그리드(Market cap/P-E/Dividend Yield/52주 고저 등) — 표준적이지만 라벨-값 배치가 깔끔함
- 회사 프로필 카드(CEO/본사/설립연도/직원수)

**채택할 만한 것**
- **자연어 요약 문단**은 monticker의 이벤트 중심 정체성과 정확히 맞닿는 지점이다. 이미 `ANTHROPIC_API_KEY`로 AI 요약 인프라가 있으니([external-apis.md](external-apis.md)), 종목 상세 페이지 상단에 "오늘 이 종목에 무슨 일이 있었는지" 한 문단 요약을 붙이는 건 낮은 비용으로 높은 체감 효과를 낼 수 있음

**적합하지 않은 것**
- 극단적 화이트스페이스 미니멀리즘 자체는 Dracula 다크 테마 우선 기조와 안 맞음 — 여백을 넉넉히 쓴다는 태도만 참고

---

## monticker 페이지별 적용 매핑

| monticker 페이지 | 참고 서비스 | 적용할 것 | 우선순위 |
|---|---|---|---|
| [체결엔진](apps/web/src/app/matching/page.tsx) | qfex/Hyperliquid | 오더북 heatmap 바, 미체결/체결 내역 탭 통합, 매수/매도 스플릿 버튼 | 높음 — 가장 직접적인 대상 |
| [스크리너 (홈)](apps/web/src/app/page.tsx) | Finviz | 필터/표시컬럼 분리, 관심종목·알림 바로가기 동선 | 중간 |
| [SearchAutocomplete](apps/web/src/components/stock/SearchAutocomplete.tsx) | qfex | 카테고리 탭 + 스파크라인 + 1D 변동률 | 중간 |
| [TradeModal](apps/web/src/components/paper/TradeModal.tsx) | 토스증권 | 수량 퀵버튼(10%/25%/50%/100%) | 낮음 — 작은 개선 |
| [Stock Detail](apps/web/src/app/stocks/%5Bsymbol%5D/page.tsx) | Robinhood | AI 자연어 요약 문단 | 높음 — 포지셔닝과 직결 |
| [리스크](apps/web/src/app/risk/page.tsx) / [Analytics](apps/web/src/app/analytics/page.tsx) | 토스증권 | 개인·외국인·기관 순매수 시각화 | 낮음 — 데이터 소스 확보 필요 |
| 종목 상세 (전반) | TradingView | 차트 위 현재가 기준 매수/매도 퀵 버튼 | 중간 |

## 명시적으로 채택하지 않기로 한 것

- **레버리지/마진 관련 UI 전반** (Cross, 청산가, Reduce Only) — monticker는 모의투자(현물)만 다룸
- **Pine Editor류 코드 스크립팅** — Quant Lab의 no-code 철학과 정면으로 배치
- **위젯 드래그앤드롭 커스터마이징** (토스증권) — 지금 단계엔 과한 투자
- **Binance Futures급 정보 밀도** — Dracula 팔레트로 확보한 여백 기조와 충돌
- **Robinhood식 화이트 미니멀리즘** — 다크 테마 우선 기조와 안 맞음, "여백을 넉넉히" 라는 태도만 차용

## 아직 안 본 것 (필요시 이어서 조사)

- dYdX / GMX — Hyperliquid과 같은 계열이라 낮은 우선순위
- 카카오페이증권, 삼성증권 mPOP, 키움증권 영웅문 — 국내 경쟁사 직접 비교가 필요해지면
- Koyfin, Simply Wall St — 스토리텔링형 종목 분석 UI, Analytics 페이지 개선 시 참고 후보

## 관련 문서

- [Product](product.md) — Key Screens, Navigation
- [quant-lab-positioning.md](domain/quant-lab-positioning.md) — Quant Lab이 no-code인 이유
- [investment-wallet-ux-philosophy.md](domain/investment-wallet-ux-philosophy.md) — 지갑/영수증 UX 철학
