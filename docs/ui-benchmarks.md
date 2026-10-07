# UI 벤치마크

monticker의 화면 설계를 개선할 때 참고하기 위해 조사한 외부 서비스 모음이다. 각 서비스에서 무엇이 좋았는지, monticker의 어떤 화면에 어떻게 적용할지, 그리고 **의도적으로 채택하지 않은 것**까지 정리한다. 새 화면을 설계하거나 기존 화면을 리디자인할 때 이 문서를 먼저 확인한다.

이 문서는 결정(ADR)이 아니라 **재료 모음**이다. 여기서 실제로 특정 패턴을 채택하기로 결정하면(예: 화면 구조를 코드 편집기 기반으로 바꾼다 같은 큰 결정), 별도 ADR을 `docs/decisions/`에 작성한다.

## 조사 대상

| 서비스 | 카테고리 | URL |
|---|---|---|
| **Binance Spot** | 현물 트레이딩 터미널 (리테일 대중형, 최대 사용자 수) | binance.com/en/trade/BTC_USDT |
| **Bybit Perpetual** | 파생상품 트레이딩 터미널 (UI 완성도 높음) | bybit.com/trade/usdt/BTCUSDT |
| qfex | 파생상품(레버리지) 트레이딩 터미널 | qfex.com/trade/US100-USD |
| Hyperliquid | 파생상품 트레이딩 터미널 (qfex류의 원조) | app.hyperliquid.xyz |
| 토스증권 | 한국 주식 앱 (위젯 대시보드) | tossinvest.com |
| Finviz | 미국 주식 스크리너 | finviz.com/screener.ashx |
| TradingView | 차트 · 전략 스크립팅 · 소셜 | tradingview.com |
| Robinhood | 미국 주식 앱 (마케팅 페이지) | robinhood.com |

---

## 서비스별 벤치마킹

### Binance Spot — 고밀도 멀티패널 터미널 (2026-09-30 직접 분석)

실제 `binance.com/en/trade/BTC_USDT?type=spot`를 분석한 결과다.

#### 레이아웃 구조

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  로고 | Buy Crypto | Markets | Trade | Futures | Earn | Square | AI | 검색 | 로그인  │
├──────────────────────────────────────────────────────────────────────────────┤
│  BTC/USDT  83,304.92  24h Chg -0.81%  24h H 84,563  24h L 82,900  Vol 12,794 BTC  │
├─────────────┬───────────────────────────────────────────┬────────────────────┤
│  Order Book │  Chart | Info | Data | Square [AI 버튼]   │  [검색창]           │
│             │  ┌─────────────────────────────────────┐  │  [카테고리 탭:      │
│ Price│Amt│Tot│  │  1s 15m 1H 4H 1D 1W  Original/TV  │  │  USDC USDT U USD1] │
│ 83,307↑  ...│  │  MA(7) MA(25) MA(99) 표시           │  │  [태그:All/New/     │
│ 83,306↑  ...│  │  캔들 차트 + 거래량 오실레이터       │  │  Hot/bStocks/등]   │
│ 83,305↑  ...│  │                                     │  │  Name│Price│24h%  │
│  ──────── ──│  └─────────────────────────────────────┘  │  0G/USDC 0.34 +18%│
│  83,304.92  │  Spot | Cross | Isolated | Grid            │  AAVE/USDC 159 -2%│
│ 83,304↓  ...│  [Limit] [Market] [Stop Limit]            │  ──────────────────│
│ 83,303↓  ...│                                           │  Market Trades     │
│             │                                           │  My Trades         │
│ B 40% ─ S 59│                                           │  Price│Amt│Time    │
├─────────────┴───────────────────────────────────────────┴────────────────────┤
│  SOL -0.91%  ETH -1.38%  BNB -0.81%  ...  (실시간 하단 틱커 스트립)              │
└──────────────────────────────────────────────────────────────────────────────┘
```

#### 핵심 UI 패턴 (직접 관찰)

**1. 5개 정보 패널의 동시 가시성**
- 왼쪽: 오더북 (호가 + 잔량 + 누적, 가격 정밀도 선택 가능 `0.01`)
- 중앙: 차트 (기본 MA7/MA25/MA99 오버레이, 캔들 + 거래량 오실레이터)
- 중앙 하단: 주문 패널 (탭으로 주문 타입 전환, 같은 공간에서 매수/매도 동시 입력)
- 오른쪽 상단: 종목 리스트 (카테고리 이중 필터링)
- 오른쪽 하단: 실시간 체결 피드 (Market Trades / My Trades 탭)

**2. 이중 필터링 종목 리스트**
```
Row 1: USDC | USDT | U | USD1 | USDB | BNB | BTC | ALTS | FIAT
Row 2: All | New | bStocks | Hot | tCommodities | Solana | RWA | Layer 1/2 | AI | MEME | ...
```
두 차원의 필터가 항상 노출. 필터 결과 테이블에는 Name / Last Price / 24h Chg가 한 줄로.

**3. 매수/매도 비율 바**
오더북 하단에 `B 40.86% ──────── S 59.13%` 형태의 양방향 비율 표시. 현재 오더북 불균형을 한눈에.

**4. 상단 24h 스탯 바**
`BTC/USDT` 로고 오른쪽에 `24h Chg | 24h High | 24h Low | 24h Vol(BTC) | 24h Vol(USDT)` 5개 지표가 한 줄. 차트를 보지 않아도 하루 변동폭이 즉시 파악됨.

**5. 하단 실시간 틱커 스트립**
`SOL/USDT -0.91% · ETH/USDT -1.38% · BNB/USDT -0.81% · ...` 페이지 최하단을 가로로 흐르는 멀티 종목 실시간 스트립. 현재 보고 있는 종목 외의 시장 전반 상황을 놓치지 않게 함.

**6. AI 버튼 차트 내장**
차트 영역 우상단에 AI 버튼이 있어 차트를 보다가 자연스럽게 AI 분석 접근.

**7. 차트 정보 헤더 (OHLCV 한 줄)**
차트 상단에 `Open 83,663 High 83,731 Low 82,956 Close 83,304 CHANGE -0.42% Range 0.92%` + MA 값들이 항상 표시. 마우스 이동 시 해당 캔들 값으로 업데이트.

**채택할 만한 것**
- 이중 차원 종목 리스트 필터 (현재 monticker 스크리너의 pill 필터를 더 조밀하게)
- 매수/매도 비율 바 → [OrderBook](../apps/web/src/components/stock/OrderBook.tsx)에 적용 가능
- 차트 OHLCV 헤더 상시 표시
- AI 버튼 차트 영역 내 노출 (현재는 별도 탭)
- 하단 멀티 종목 스트립 (WatchlistTicker가 상단에 있는데 하단 고정으로 보완)

**적합하지 않은 것**
- Cross/Isolated/Grid 주문 타입 — 레버리지 파생상품 전용
- 24h Vol(USDT) 수십억 단위 — 크립토 특화 표기, 한국 주식에는 거래대금/거래량으로 대체

---

### Bybit Perpetual — 클린 레이아웃 + 툴바 중심 UX (2026-09-30 직접 분석)

실제 `bybit.com/en/trade/usdt/BTCUSDT`를 분석한 결과다.

#### 레이아웃 구조

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  로고  [≡]  BTC/USDT  83,233.50  Change -0.85%  FundingRate 0.0100%/7:59:41 │
├──┬───────────────────────────────────────────────┬────────────┬──────────────┤
│  │  Chart | Overview | Data | Feed               │ Order Book │  Tr...       │
│🔧│  ┌─────────────────────────────────────────┐  │ Price│Qty│Tot│            │
│☆ │  │  Standard | TradingView | Depth         │  │ 83,235↑  4.1│            │
│📍│  │  [시간선택: 1s 1m 5m 15m 30m 1h 4h]    │  │ 83,234↑  2.2│ Cross 10x  │
│🔔│  │  [드로잉 도구 아이콘바]                  │  │ ──────────── │ Limit|Market│
│📊│  │  캔들 + 볼린저밴드 + 거래량              │  │ 83,233.40    │ Chase Limit │
│✎ │  └─────────────────────────────────────────┘  │ 83,233↓  3.2│ [수량 0-100%│
│🔍│                                               │ 83,232↓  2.9│ 바 슬라이더]│
│  │  Contract Details: Exp=Perpetual Index=83,295 │            │ Value/Cost  │
├──┴───────────────────────────────────────────────┴────────────┴──────────────┤
│  Open Orders | Positions | Order History | Trade History | Assets | Tools | P&L│
├──────────────────────────────────────────────────────────────────────────────┤
│  XDPUSDT↑  RUMUSDT -2.88%  CVNAUSDT +3.43%  BTCUSDT -0.85%  (하단 틱커)     │
└──────────────────────────────────────────────────────────────────────────────┘
```

#### 핵심 UI 패턴 (직접 관찰)

**1. 왼쪽 아이콘 툴바 (Bybit만의 독특한 패턴)**
차트 왼쪽에 세로 아이콘 바: 즐겨찾기, 포지션, 알림, 지표, 드로잉 도구, 검색 등이 아이콘으로 상시 노출. 클릭 시 패널이 열리거나 모드가 전환된다. 상단 탭 없이 빠른 기능 접근이 가능.

**2. 차트 타입 전환 — Standard / TradingView / Depth**
`Standard`: Bybit 자체 차트 (빠름, 기본 지표)
`TradingView`: 완전한 TV 차트 (Pine 스크립트 포함)
`Depth`: 오더북 시각화 (매수/매도 누적 depth 곡선)
→ **사용자 숙련도에 따라 차트를 선택**할 수 있다는 Progressive Disclosure 패턴.

**3. 하단 탭 패널 — 7개 탭 통합**
```
Open Orders | Positions | Order History | Trade History | Assets | Borrowings | Tools | P&L
```
화면 하단 고정 영역에 미체결/포지션/내역이 전부 탭으로 통합. 별도 페이지 이동 없이 현황 파악 가능.

**4. 레버리지 + 주문 타입이 한 패널**
`Cross 10.00x` 배지 클릭으로 레버리지 조정, 아래에서 바로 `Limit | Market | Chase Limit` 전환. 주문 수량은 슬라이더(0–100%) + 숫자 입력 병행.

**5. Contract Details 항상 노출**
차트 하단에 `Expiration / Index Price / Mark Price / Open Interest / 24H Turnover / 24H Volume / Contract Value`가 접힌 상태로 있지만 펼칠 수 있음. 파생상품 필수 지표가 주문 전에 노출.

**6. Feed 탭**
차트 영역에 `Chart | Overview | Data | Feed` 탭이 있고, Feed는 뉴스·소셜 피드. monticker의 이벤트 중심 컨셉과 구조가 같다.

**7. Demo Trading 버튼**
주문 패널 하단에 "Demo Trading" 링크 상시 노출. 실거래 진입 전 연습할 수 있다는 신뢰 장치.

**채택할 만한 것**
- 왼쪽 아이콘 툴바 패턴 → 차트 페이지/종목 상세에서 빠른 기능 전환 (현재 monticker는 탭 위주)
- Standard / Advanced 차트 전환 → 초보자 vs 숙련자 Progressive Disclosure
- 하단 7탭 통합 패널 → 주문/내역이 흩어진 현재 구조 개선
- Feed 탭 → 이벤트/뉴스를 차트와 같은 공간에 놓는 구조
- Demo Trading CTA → 모의투자 진입 유도 (현재 진입 경로가 불명확)

**적합하지 않은 것**
- 레버리지 배지(10x), Borrowings 탭, Funding Rate — 파생상품 전용
- Chase Limit 주문 타입 — 크립토 호가 빠른 추격 주문, 한국 주식엔 불필요

---

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
- qfex의 심볼 검색 모달: 카테고리 필터 탭(All/관심종목/Equities/...) + 종목당 미니 스파크라인 + 1D 변동률 + 즐겨찾기 별표를 한 줄에.
  카테고리 탭 + 1D 변동률은 [SearchAutocomplete](../apps/web/src/components/stock/SearchAutocomplete.tsx)에 적용 완료 — `/api/screener/search`가 이미 가격·등락률을 한 번에 조인해서 반환해서 백엔드 변경 없이 됐다.
  **스파크라인은 보류**: 결과당 캔들 시리즈를 따로 조회해야 하는데, 키 입력마다 최대 20개 종목 × 캔들 API 호출은 배치 엔드포인트 없이는 N+1이 됨 — 배치 캔들 조회 API가 생기면 재검토.

**적합하지 않은 것**
- Cross/레버리지 배지, Reduce Only, 청산가(Liquidation Price) 같은 레버리지 파생상품 전용 개념 — monticker는 모의투자(현물)이므로 그대로 가져올 개념 자체가 없음

---

### 토스증권 — 위젯 대시보드 + 한국 시장 데이터

가장 다른 설계 철학. 고정 3분할이 아니라 **카드형 위젯 그리드**이고, 각 위젯에 `×`(제거)/`+`(추가) 컨트롤이 있어 사용자가 대시보드를 직접 편집한다("레벨 편집").

**채택할 만한 것**
- **로그인 게이트 패턴**: 호가창처럼 로그인이 필요한 위젯은 깨진 화면 대신 위젯 내부에 "호가를 보려면 로그인이 필요해요" + CTA 버튼만 표시 — 페이지 전체를 막지 않고 위젯 단위로 처리
- **개인·외국인·기관 순매수 위젯**: 수평 바 차트 + 일별 테이블 — 한국 시장 특유의 수급 데이터라 crypto 레퍼런스엔 없던 것. ✅ 완료
- 주문 패널의 수량 퀵버튼(10%/25%) — ✅ 완료
- 종목 상세 페이지에 뉴스·이벤트가 1급 시민으로 붙어있는 구조

**적합하지 않은 것**
- 위젯 드래그앤드롭 커스터마이징 자체는 구현 비용 대비 monticker 현재 단계에 과함

---

### Finviz — 다차원 스크리너

**채택할 만한 것**
- **필터 ≠ 표시 컬럼 분리** 원칙 — ✅ 완료 ([ADR-018](decisions/018-stock-fundamentals-kis-reuse.md))
- 스크리닝 결과에서 바로 "관심종목 추가" / "알림 만들기"로 이어지는 동선 — ✅ 완료

**적합하지 않은 것**
- 5×5 그리드 수준의 다차원 필터(Analyst Recom, Short Float, IPO Date 등) — 미국 주식 펀더멘털 데이터 의존도가 높음. 점진적으로 필터 축 2~3개만 추가하는 쪽이 맞음

---

### TradingView — 차트 · 전략 스크립팅 · 소셜

**채택할 만한 것**
- 차트 위에 뜨는 현재가 기준 매수/매도 퀵 버튼 — ✅ 완료

**적합하지 않은 것**
- Pine Editor류 코드 에디터 — Quant Lab의 no-code 철학과 정면으로 배치

---

### Robinhood — 미니멀 + AI 자연어 요약

**채택할 만한 것 → 실제로는 이미 있었음**
- [SummaryPanel](../apps/web/src/components/stock/SummaryPanel.tsx) + [StockSummaryService](../backend/api/src/main/kotlin/com/monticker/api/ai/StockSummaryService.kt)로 이 기능이 이미 붙어 있었다.
- [NewsPanel](../apps/web/src/components/stock/NewsPanel.tsx) 실데이터 연결 — ✅ 완료

**적합하지 않은 것**
- 극단적 화이트스페이스 미니멀리즘 — Dracula 다크 테마 우선 기조와 안 맞음

---

## 데스크톱 레이아웃 전면 재편 — 진단과 방향 (2026-09-30)

### 현재 문제: 단조로운 단일 컬럼

현재 monticker 데스크톱 뷰는 사실상 **모바일 레이아웃을 늘린 것**에 가깝다.

```
현재 홈(/)
┌──────────────────────────────────────┐
│  WatchlistTicker (상단 1줄)           │  ← 유일한 실시간 멀티 데이터
│  필터 pill                            │
│  ScreenerTable (단일 컬럼)            │  ← 정보가 여기 하나뿐
│  ...                                 │
└──────────────────────────────────────┘

현재 종목 상세(/stocks/[symbol])
┌──────────────────────────────────────┐
│  차트 (상단 절반)                     │
│  탭: 개요 | 뉴스 | 이벤트 | AI | ...  │  ← 한 번에 하나만 보임
│  탭 내용 (세로로 긴 단일 카드들)       │
└──────────────────────────────────────┘
```

**핵심 문제**: Binance/Bybit에서 동시에 보이는 정보량의 20% 수준. 나머지 80%는 탭 뒤에 숨어있거나 스크롤해야 보인다.

### 목표 레이아웃 원칙

트레이딩 터미널 수준의 정보 밀도를 **monticker의 주식 관찰 컨텍스트**에 맞게 재해석한다.

```
원칙 1: 동시 가시성 — 핵심 정보 4개 이상이 스크롤 없이 한 화면에
원칙 2: 라이브 우선 — 모든 숫자는 WebSocket으로 살아움직여야 의미가 있음
원칙 3: Progressive Complexity — 기본 모드(초보자)와 고급 모드(숙련자) 전환 가능
원칙 4: 패널 독립성 — 각 패널이 서로 독립적으로 정보를 제공하되, 클릭 시 연동
원칙 5: 탭 최소화 — 탭은 같은 영역의 "관점 전환"에만. 정보 숨김 수단으로 쓰지 않음
```

---

### 화면별 재편 방향

#### A. 홈 대시보드 — 4분할 그리드로 전환

```
┌────────────────────────────────────────────────────────────────────────────┐
│  [상단 실시간 틱커] 삼성전자 ▼0.8%  SK하이닉스 ▲1.2%  현대차 ...          │
├──────────────────────┬─────────────────────────┬───────────────────────────┤
│  포트폴리오 스냅샷    │  시장 요약               │  TOP 이벤트               │
│  ──────────────────  │  ─────────────────────  │  ─────────────────────    │
│  총 평가액  ₩12.4M   │  KOSPI   2,512 ▼0.3%   │  🔴 삼성전자 5% 급등       │
│  오늘 손익  +₩82K    │  KOSDAQ  780   ▲0.1%   │  📰 SK하이닉스 실적 발표   │
│  수익률     +0.67%   │  USD/KRW 1,342          │  📊 현대차 거래량 급증     │
│  [미니 차트: 7일]    │  [시장별 히트맵]          │  [더보기 →]               │
├──────────────────────┼─────────────────────────┴───────────────────────────┤
│  관심종목 요약        │  스크리너 (이하 기존과 동일)                          │
│  ──────────────────  │  [필터 pill] [컬럼 토글]                              │
│  삼성전자  ▼0.8%     │  ScreenerTable                                       │
│  SK하이닉스 ▲1.2%   │  ...                                                  │
│  현대차    ▼0.3%     │                                                       │
│  [+ 관심종목 추가]   │                                                       │
└──────────────────────┴───────────────────────────────────────────────────────┘
```

**구현 포인트**
- 상단 4개 카드(`PortfolioSnapshot`, `MarketSummary`, `RecentEvents`, `WatchlistSummary`)는 이미 컴포넌트가 완성되어 있음 (코드 확인: `apps/web/src/components/home/` 5개 파일). page.tsx에서 import만 하면 됨.
- 4개 카드를 CSS Grid `grid-cols-3` + `row-span` 조합으로 배치
- 현재 하드코딩된 hex(`#ff5050`/`#4a8fd4`) → Dracula 토큰(`text-market-up`, `text-market-down`)으로 교체 필요

---

#### B. 종목 상세 — 4패널 고정 레이아웃 (Binance/Bybit 구조 차용)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  삼성전자(005930)  75,000  ▼600 (-0.8%)  고가 75,600  저가 74,800  거래량 1.2M  │
├──────────┬─────────────────────────────────────────────────────┬─────────────┤
│ 호가창    │  차트          | 개요 | 뉴스/이벤트 | AI요약         │  관련 지표   │
│          │  ┌───────────────────────────────────────────────┐  │  ──────────  │
│ 매도잔량  │  │  [Standard] [Advanced]    [드로잉]  [AI]      │  │  PER   12.3 │
│ ──────   │  │  MA5  MA20  EMA  볼린저     RSI MACD          │  │  PBR    1.2 │
│ 75,100↑  │  │  캔들 차트                                    │  │  시총  435조 │
│ 75,050↑  │  │                                               │  │  ──────────  │
│ 75,000   │  │  [거래량 오실레이터]                           │  │  외국인  ─2%│
│ 74,950↓  │  └───────────────────────────────────────────────┘  │  기관   +1% │
│ 74,900↓  │                                                     │  개인   +1% │
│ ──────   │  [매수] [매도]  수량 [ ] 가격 [ ]   예상금액 [ ]    │  ──────────  │
│ 매수잔량  │                                                     │  52주H 88,800│
│          │                                                     │  52주L 55,200│
│ B% ─ S%  │                                                     │             │
├──────────┴─────────────────────────────────────────────────────┴─────────────┤
│  미체결 주문 | 체결 내역 | 관심종목 | 조건부 주문                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

**구현 포인트**
- 레이아웃: `grid-cols-[200px_1fr_180px]` + 하단 탭 패널 고정
- 왼쪽 호가창: [OrderBook](../apps/web/src/components/stock/OrderBook.tsx) 현재 있음, Binance 스타일 배경 바 heatmap 추가
- 중앙 상단 스탯 바: 현재 `StockDetailClient` 상단에 텍스트만 있음, 5개 지표 한 줄 정렬
- 우측 관련 지표 패널: `InvestorFlowPanel` 재배치 + 펀더멘털 지표 추가
- 하단 탭 패널: 현재 `/wallet`, `/brokerage/orders` 등으로 흩어진 것 통합
- Standard/Advanced 전환: Standard=MA5/MA20, Advanced=볼린저+RSI+MACD

---

#### C. 매칭/주문 페이지 — 현재 가장 밀도가 낮음

Bybit 하단 탭 패널 패턴을 그대로 채용:

```
현재: 카드를 세로로 나열 (스크롤 필요)
개선: 주문 입력(좌) + 오더북(중) + 체결내역(우) 3분할 + 하단 탭 (미체결/내역/자산)
```

---

#### D. Quant Lab — 2분할 워크스페이스

현재 단일 컬럼 빌더를 2분할로:

```
┌──────────────────────────┬────────────────────────────────┐
│  룰셋 빌더               │  백테스트 결과                  │
│  [조건 블록 드래그]       │  [수익률 차트]                  │
│  [IF → THEN → 주문]      │  [거래 신호 타임라인]           │
│                          │  [성과 지표: Sharpe/MDD]       │
└──────────────────────────┴────────────────────────────────┘
```

---

### 정보 밀도 달성을 위한 CSS 전략

```css
/* Dracula 팔레트 기반 고밀도 레이아웃 토큰 */
--density-compact: 0.25rem;   /* 행 간격 최소 */
--density-normal: 0.5rem;     /* 패널 내부 기본 */
--density-panel: 1rem;        /* 패널 간 분리 */

/* 숫자 폰트: tabular-nums 강제 */
.stat-value { font-variant-numeric: tabular-nums; }

/* 패널 고정 레이아웃 */
.terminal-layout {
  display: grid;
  grid-template-rows: auto 1fr auto;  /* 상단 바 + 메인 + 하단 탭 */
  height: 100vh;
  overflow: hidden;
}

/* 메인 3분할 */
.terminal-main {
  display: grid;
  grid-template-columns: 200px 1fr 180px;
  overflow: hidden;
}

/* 각 패널 독립 스크롤 */
.panel { overflow-y: auto; height: 100%; }
```

**배경색 계층 (Dracula)**
```
페이지 배경:  #282a36 (dracula-bg)
패널 배경:    #1e2029 (dracula-bg보다 어두운 변형)
패널 헤더:    #44475a (dracula-line)
강조 셀:      #6272a4/20% (dracula-selection/20%)
```

---

## monticker 페이지별 적용 매핑 (최신)

| monticker 페이지 | 참고 서비스 | 적용할 것 | 우선순위 |
|---|---|---|---|
| [홈(/)대시보드](../apps/web/src/app/page.tsx) | Binance 상단 바 + Bybit 4분할 | 포트폴리오/시장요약/이벤트/관심종목 4개 카드 배치 | 🔴 P0 (컴포넌트 완성, import만 필요) |
| [종목 상세](/stocks/[symbol]) | Binance 5패널 + Bybit 좌측 툴바 | 4패널 고정 레이아웃 + 상단 스탯 바 + 하단 탭 통합 | 🔴 P0 |
| [체결엔진](/matching) | Binance + Bybit | 3분할(호가창+차트+체결피드) + 하단 탭 통합 | 🟠 P1 |
| [Quant Lab](/quant-lab) | Bybit 2분할 워크스페이스 | 빌더+결과 2분할 | 🟠 P1 |
| 스크리너 필터 | Binance 이중 탭 필터 | quote currency 탭 + 카테고리 태그 탭 이중 구조 | 🟡 P2 |
| [OrderBook](../apps/web/src/components/stock/OrderBook.tsx) | Binance/qfex | heatmap 배경 바, 매수/매도 비율 바 | 🟡 P2 |
| 차트 | Bybit Standard/Advanced 전환 | 초보/고급 차트 모드 토글 | 🟡 P2 |
| [체결엔진](/matching) | qfex/Hyperliquid | 오더북 heatmap 바, 미체결/체결 내역 탭 통합, 매수/매도 스플릿 버튼 | ✅ 완료 |
| [Stock Detail](/stocks/[symbol]) | Robinhood | AI 요약에 가격 동향(당일 등락률·거래범위) 반영, NewsPanel 실데이터 연결 | ✅ 완료 |
| [종목 검색](../apps/web/src/components/stock/StockSearch.tsx) (구 SearchAutocomplete, ADR-066 전환) | qfex | 카테고리 탭 + 1D 변동률 | ✅ 완료 (스파크라인만 보류) |
| [스크리너](../apps/web/src/app/screener/page.tsx) (ADR-066 이후 `/screener`) | Finviz | 필터/표시컬럼 분리, 관심종목·알림 바로가기 동선 | ✅ 완료 ([ADR-018](decisions/018-stock-fundamentals-kis-reuse.md)) |
| [TradeModal](../apps/web/src/components/paper/TradeModal.tsx) | 토스증권 | 수량 퀵버튼(25%/50%/75%/100%) | ✅ 완료 |
| 종목 상세 (전반) | TradingView | 차트 위 현재가 기준 매수/매도 퀵 버튼 | ✅ 완료 |
| [Stock Detail](../apps/web/src/components/stock/InvestorFlowPanel.tsx) | 토스증권 | 개인·외국인·기관 순매수 시각화 | ✅ 완료 ([ADR-017](decisions/017-investor-flow-kis-integration.md)) |

---

## 명시적으로 채택하지 않기로 한 것

- **레버리지/마진 관련 UI 전반** (Cross, 청산가, Reduce Only, Funding Rate) — monticker는 모의투자(현물)만 다룸
- **Pine Editor류 코드 스크립팅** — Quant Lab의 no-code 철학과 정면으로 배치
- **위젯 드래그앤드롭 커스터마이징** (토스증권) — 지금 단계엔 과한 투자
- **Robinhood식 화이트 미니멀리즘** — 다크 테마 우선 기조와 안 맞음, "여백을 넉넉히" 라는 태도만 차용
- **Binance급 정보 폭격** (1,000개 알트코인 리스트 + 30개 이상 카테고리 태그) — 202개 종목에 특화된 monticker는 선택과 집중. 이중 탭 필터 구조만 차용하고 카테고리 수는 5개 이하로 제한

---

## 추가 조사 완료 (2026-09-01) — 실제 착수는 안 함

### dYdX / GMX — 신규 채택 없음

dYdX v4는 Cosmos SDK 기반 자체 체인 위의 완전 탈중앙 오더북(CLOB) 방식, GMX는 오라클(Chainlink/Pyth) 기반 제로 슬리피지 방식으로 매칭 아키텍처는 다르지만, **거래 터미널 UI 자체는 Hyperliquid과 같은 카테고리**라 이미 벤치마킹한 것 이상의 새로운 UI 패턴은 없었다.

### 국내 경쟁사 (카카오페이증권 / 삼성증권 mPOP / 키움증권 영웅문) — 신규 채택 없음

Opensurvey의 국내 MTS 앱 UX 비교 조사에 따르면 **토스증권이 UX·Offering 양쪽 다 최고점(73.2점)**을 받았고, 나무증권(68.4점)·M-STOCK(67.2점)이 뒤를 이었다. 이미 monticker가 벤치마킹 1순위로 삼은 토스증권이 실제로도 UX 최고점. 영웅문급 정보 밀도는 명시적 배제.

### Koyfin / Simply Wall St — Analytics 페이지용 아이디어로 남겨둠 (미착수)

- **Simply Wall St**: **"Snowflake" 5축 펜타곤 시각화** — Valuation/Future Growth/Past Performance/Financial Health/Dividends 5개 축. monticker의 Analytics 페이지에 적용할 만한 구체적인 후보. Analytics 페이지 개선 시 재검토.

---

## monticker 실제 화면 갭 분석 (2026-09-09)

별도 작성된 대형 벤치마크 보고서 [securities_app_ui_benchmark_2026_global_desktop_expanded.docx](securities_app_ui_benchmark_2026_global_desktop_expanded.docx)(2026-09-05, 국내 6개+해외 9개 증권앱을 모바일·PC 양쪽으로 심층 분석)에서 `frontend-reviewer` 서브에이전트로 체크리스트를 monticker 코드와 항목별로 대조한 결과다.

### 화면별 체크리스트 대조 결과

| 영역 | 체크 항목 | 상태 | 근거 |
|---|---|---|---|
| 홈 | 5초 내 총자산/오늘 변화/이해 | **없음** | [page.tsx](../apps/web/src/app/page.tsx)는 스크리너(종목 랭킹)만 렌더링. [components/home/](../apps/web/src/components/home/)에 `PortfolioSnapshot`/`MarketSummary`/`RecentEvents`/`WatchlistSummary`/`TopMovers` 5개 컴포넌트가 **이미 완성돼 있으나 어디에서도 import되지 않는 고아 코드** |
| 홈 | 개인화가 밀도/순서를 바꾸는가 | 부분적 | `columnSet`(기본/밸류에이션) 토글은 있으나 `useState`뿐이라 새로고침하면 초기화 |
| 탐색 | 랭킹 외 설명형·고급 조건검색 | **없음** | 고정 pill 필터만 있고 커스텀 조건식 빌더·테마 탐색 없음 |
| 탐색 | 스크리너 결과 → 관심/알림/주문 전환 | 있음 | [ScreenerRow.tsx](../apps/web/src/components/screener/ScreenerRow.tsx) 별표·종 실동작 확인 |
| 관심종목 | 최근 본 종목 vs 관심종목 구분 | **없음** | "최근 본 종목" 개념 자체가 코드에 없음 |
| 관심종목 | 그룹/정렬/컬럼/세션/알림 규칙 | 부분적 | 그룹 생성은 있으나 정렬·표시컬럼 없음 |
| 종목 상세 | 가격/차트/뉴스/이벤트/커뮤니티 연결 | 부분적 | 탭으로 물리적 공존은 하나, 차트 이벤트 마커 클릭→뉴스 점프 같은 cross-navigation 없음 |
| 차트 | 기본 사용자가 학습 없이 읽는가 | 있음 | 캔들+거래량+MA5/MA20+현재가 라인 기본 표시 |
| 차트 | 고급 지표/드로잉/주문선 | **없음** | 드로잉 툴, 차트 위 주문선 드래그, 자유 지표 추가 전혀 없음 |
| 주문 | 미체결/조건주문/체결내역 통합 뷰 | 부분적 | 일반주문·조건부주문·모의투자 체결이 최소 2~3개 페이지로 분산 |
| 자산 | 배당/현금/성과원인 이해 | 부분적 | 배당 기능 자체가 없음. "왜 오늘 포트폴리오가 변했는지" 이벤트 기반 귀인 설명 없음 |
| 자산 | 목표/전략 단위로 자산 묶어보기 | **없음** | Quant Lab 전략과 실제 보유종목을 연결하는 뷰 없음 |
| 자동화 | 정기매수(적립식) | **없음** | 코드에 개념 자체가 없음 |
| 알림 | 이벤트/기술조건/보유상태 기반 알림 | 부분적 | 가격·거래량급증만 있고 RSI/이평선 교차, "보유종목 -N%" 없음 |
| 신뢰 | AI/자동화 위험고지가 행동 직전에 보이는가 | **있음** | [OrderProposalCard.tsx](../apps/web/src/components/ai/OrderProposalCard.tsx)의 `DEFAULT_DISCLAIMER` — 잘 되어 있음 |

### 우선순위 후보 (비용 대비 임팩트)

1. **홈 대시보드 연결** — 컴포넌트 완성, import만 필요. `PortfolioSnapshot.tsx` 등이 실제 API 호출 코드까지 갖추고 있음. 색상 토큰 교체만 하면 배포 가능.
2. **종목 상세 4패널 레이아웃** — 가장 자주 쓰이는 화면. 탭 뒤에 숨은 정보를 동시 가시성으로 전환.
3. **최근 본 종목** — localStorage만으로 구현 가능, 서버 변경 불필요.
4. **주문 사후관리 통합(하단 탭 패널)** — 일반/조건부/모의투자 주문 상태가 흩어진 문제.
5. **차트 Standard/Advanced 모드** — 구현 비용이 크지만 Quant Lab/체결엔진 다음 확장 후보.

## 관련 문서

- [Product](product.md) — Key Screens, Navigation
- [quant-lab-positioning.md](domain/quant-lab-positioning.md) — Quant Lab이 no-code인 이유
- [investment-wallet-ux-philosophy.md](domain/investment-wallet-ux-philosophy.md) — 지갑/영수증 UX 철학
- [ADR-023](decisions/023-commercialization-pivot.md) — 상용화 피벗 결정 (UI 방향 근거)
