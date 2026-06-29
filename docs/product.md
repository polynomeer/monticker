# monticker — Product

> Read this when: deciding what to build, scoping a feature, or checking MVP boundaries.

## Product Identity

monticker is an **event-centric stock observation platform with a built-in quant strategy lab and investment wallet**.

The original core value:

> Show *why* a price moved — by overlaying news, disclosures, volume anomalies, and sentiment signals directly onto the chart timeline.

Extended with Quant Lab:

> Let users turn investment ideas into verifiable rules, backtest them against real history, validate them forward in a live paper environment, and optionally share or sell the strategy — without exposing the underlying ruleset.

Extended with Investment Wallet:

> 사용자가 투자 과정에서 자신의 현금·예약금·체결금·정산 대기금이 어떻게 이동하는지 실시간으로 이해할 수 있도록 원장 기반 타임라인을 설계했습니다. 주문 시점의 감정 태그와 체결 결과를 연결해 사용자가 자신의 투자 습관을 복기할 수 있는 기능을 제공합니다.

**Conventional app:**
```
Samsung 70,000 KRW  +2.1%  [chart]  [news list]
```

**monticker:**
```
Samsung 10:24 spike
  Volume: 4.8× above 5-min average
  News: "HBM supply expansion expected"
  Quant Lab signal: "Volume Breakout v1.2" triggered on this stock
  Forward test match rate vs backtest: 94%
```

---

## Full Service Structure

```
Monticker
├── Market View
│   ├── 실시간 시세조회 (스크리너)
│   ├── 이벤트 타임라인 차트 오버레이
│   └── 변동성 기준 해석
│
├── Portfolio Insight
│   ├── 손익 기여도 분해
│   ├── 위험 집중도 분석
│   └── 평균단가 차트 오버레이
│
├── Paper Trading + Investment Wallet
│   ├── 모의 주문 / 체결 엔진
│   ├── 체결 순간 상태머신 (주문접수→예약→체결→정산)
│   ├── 내 돈 이동 타임라인 (원장 기반)
│   ├── 투자 영수증 (체결금·수수료·정산 상태)
│   ├── 감정 태그 기반 투자 기록
│   ├── 주문 리플레이 (하루 투자 복기)
│   ├── 돈의 이동 지도 (현금/예약금/평가액/정산대기)
│   ├── 투자 행동 점수
│   └── 투자 생존 점수
│
├── Quant Lab                          ← NEW
│   ├── 룰셋 빌더 (코딩 없는 조건식 UI)
│   ├── 전략 유니버스 설정
│   ├── 백테스트 (과거 데이터 검증)
│   ├── 포워드 테스트 (실시간 시장 검증)
│   ├── 모의 자동매매
│   └── 전략 보관함 (버전 관리)
│
└── Strategy Market                    ← NEW (Phase 5)
    ├── 전략 공유 / 구독
    ├── 전략 판매
    ├── 검증 배지
    └── 룰셋 보호 (서버 사이드 실행)
```

---

## Core Feature Axes

```
monticker
├── 1. Real-time price monitoring
├── 2. Event timeline overlaid on chart
├── 3. News / disclosure / sentiment keyword mapping
├── 4. Anomaly detection (price spike, volume surge)
├── 5. Watchlist & portfolio observation
├── 6. Paper trading + Investment Wallet
│       원장 기반 돈의 이동 타임라인, 투자 영수증, 감정 태그
├── 7. Quant Lab — ruleset builder + backtest + forward test   ← NEW
└── 8. Strategy Market — share / sell verified strategies     ← NEW
```

---

## Quant Lab — Concept

### One-line definition

> 코딩 없이 투자 규칙을 만들고, 과거 데이터와 실시간 모의투자로 검증한 뒤, 검증된 전략을 비공개로 운용하거나 공유·판매할 수 있는 퀀트 전략 플랫폼.

### User value shift

```
Before Quant Lab:            After Quant Lab:
감으로 종목을 본다      →    투자 아이디어를 규칙으로 만든다
좋아 보이면 매수한다    →    과거 데이터로 검증한다
수익률을 확인한다       →    실시간 모의투자로 실험한다
왜 실패했는지 모른다    →    실패 원인을 분석한다
                             좋은 전략은 저장하거나 판매한다
```

---

## Quant Lab — Ruleset Builder

Users compose investment rules without coding using block-style UI:

```
IF
  현재가 > 20일 이동평균선
AND
  거래량 > 20일 평균 × 2
AND
  RSI BETWEEN 30 AND 70
THEN
  매수 신호 / 모의매수
```

### Supported condition blocks

| Category | Examples |
|----------|---------|
| 가격 | 현재가 > MA, N일 고점 돌파, 갭 상승률 > N% |
| 거래량 | 거래량 > N일 평균 × M, 거래대금 > N억 |
| 기술적 지표 | RSI, MACD 골든/데드크로스, 볼린저밴드, EMA |
| 포트폴리오 | 보유 비중 < N%, 현금 비중 > N% |
| 리스크 | 손절가 도달, 최대 손실 초과, 변동성 점수 |
| 이벤트 | 거래량 이상징후, 공시 발생, 관련 뉴스 발생 |

### Ruleset JSON structure (internal)

```json
{
  "name": "거래량 돌파 단기 전략",
  "universe": { "market": "KOSPI", "filters": ["market_cap > 5e11"] },
  "entryRules": {
    "operator": "AND",
    "conditions": [
      { "indicator": "close", "operator": ">", "value": "ma20" },
      { "indicator": "volume", "operator": ">", "value": "avg_volume_20d * 2" },
      { "indicator": "rsi14", "operator": "BETWEEN", "value": [30, 70] }
    ]
  },
  "exitRules": {
    "operator": "OR",
    "conditions": [
      { "indicator": "profit_rate", "operator": ">=", "value": 8 },
      { "indicator": "loss_rate",   "operator": "<=", "value": -4 }
    ]
  },
  "positionSizing": { "type": "fixed_ratio", "value": 10 }
}
```

---

## Quant Lab — Backtest

Backtest runs the ruleset against historical candle data.

**Required output metrics:**

```
총 수익률 / 연환산 수익률
최대 낙폭 (MDD)
승률 / 손익비 (Profit Factor)
거래 횟수 / 평균 보유 기간
최대 연속 손실
수수료·세금·슬리피지 반영 결과
벤치마크 대비 초과수익
시장 국면별 성과 (상승장 / 하락장 / 횡보)
```

**Reliability score (A/B/C/D)** — prevents over-optimised strategies from appearing trustworthy:

```
백테스트 신뢰도: B

감점 요인:
- 상장폐지 종목 데이터 미포함
- 슬리피지는 고정 0.15% 가정
- 훈련 기간 성과 > 검증 기간 성과 차이 큼
```

**Bias prevention (mandatory):**
- No look-ahead bias (signal evaluated only on prior candles)
- Survivorship bias disclosure
- Commission + tax + slippage always applied
- Volume filter (no fill assumed when avg_volume < threshold)
- Over-optimisation detection (parameter change count, out-of-sample gap)

---

## Quant Lab — Forward Test

After backtest, the strategy runs in real-time paper mode before any sharing.

```
포워드 테스트 14일차

발생 신호: 23개
가상 진입: 18개
평균 수익률: +1.2%
승률: 55.5%
백테스트 예상 승률: 58.1%
```

Required gate before Strategy Market listing:
```
백테스트 통과
→ 포워드 테스트 30일 이상
→ 모의 자동매매 체결 검증
→ 신뢰도 배지 부여
→ 판매/공유 가능
```

---

## Investment Wallet — Concept

### One-line definition

> 단순 잔고 숫자가 아니라 **"내 돈이 어디에 어떤 상태로 있는지"**를 원장 기반으로 실시간 추적하는 투자 월렛.

### Core features

#### 1. 내 돈 이동 타임라인

주문 시점부터 정산 완료까지 돈의 상태 전이를 원장 이벤트 스트림으로 보여준다.

```
100,000원 충전
↓ (DEPOSITED)
70,000원 매수 주문 예약
↓ (RESERVED)
35,000원 부분 체결
↓ (PARTIALLY_FILLED)
35,000원 예약 해제
↓ (UNRESERVED)
현재 사용 가능 금액 65,000원
```

백엔드 설계 포인트: 원장(ledger) 테이블, 주문 예약금 상태 머신, 이벤트 소싱 패턴.

#### 2. 투자 영수증

체결 직후 토스 스타일 영수증을 보여준다.

```
삼성전자 3주 매수 완료

주문 금액:   210,000원
체결 금액:   209,700원
수수료:           300원
남은 예약금:       0원
체결 시간:    14:31:08
결제 상태:  정산 완료
```

#### 3. 감정 태그 기반 투자 기록

주문 시점에 감정을 선택하고 나중에 수익률과 연결해 보여준다.

```
매수 이유 선택: [확신] [불안] [따라삼] [뉴스보고] [급등놓칠까봐] [장기투자]

→ 결과 분석
'급등 놓칠까 봐' 태그로 산 종목의 평균 수익률: -3.2%
'장기 투자' 태그로 산 종목의 평균 수익률:    +4.8%
```

#### 4. 돈의 이동 지도

총 자산을 상태별로 분해해서 보여준다.

```
총 자산 1,000,000원

사용 가능 현금:   200,000원
주문 예약금:      150,000원
보유 주식 평가액: 600,000원
정산 대기 금액:    50,000원
```

#### 5. 주문 리플레이

하루가 끝나면 투자 과정을 게임 로그처럼 복기할 수 있다.

```
09:10 카카오 5주 매수 주문
09:11 2주 부분 체결
09:13 나머지 3주 체결
10:20 네이버 매도 (+4.2%)
15:30 오늘 손익 +12,300원
```

#### 6. 체결 순간 상태 머신

주문 상태 전이를 실시간으로 보여준다 (단순 "매수 완료" 대신).

```
주문 접수 → 금액 예약 완료 → 시장에 주문 전송 → 일부 체결 → 전량 체결 → 정산 완료
```

#### 7. 투자 행동 점수 & 생존 점수

```
오늘의 투자 습관 점수: 78점
  좋았던 점: 분할 매수 비율 높음, 무리한 추가 매수 없음
  주의할 점: 급등 직후 추격 매수 비율 높음

투자 생존 점수: 64점
  위험 요인: 한 종목 비중 72%, 현금 비중 3%
```

Note: 실제 투자 조언이 아닌 **교육용/모의투자 전용 피드백**으로 설계.

---

## Strategy Market — Design Principles

This feature must not resemble a stock-tip service or investment advisory:

| Not this | This |
|----------|------|
| 수익률 1위 전략 | 검증 기간 기준 신뢰도 순 |
| 100% 수익 보장 | 과거 성과 + 위험 지표 모두 공개 |
| 종목 추천방 | 검증 가능한 룰셋 신호 구독 |
| 리딩방 | 모의투자 기반 전략 실험 |

**Ruleset protection:**
- Ruleset source never sent to client
- Server-side evaluation only: buyer receives signal result, not conditions
- Signal query rate-limited (prevents reverse engineering)
- `ruleSetFingerprint = SHA-256(normalizedRuleSet)` for tamper detection

**Compliance (required from day 1):**
```
과거 성과가 미래 수익을 보장하지 않습니다.
실제 투자 판단과 책임은 사용자 본인에게 있습니다.
```

---

## Key Screens

### Home (스크리너)

메인 화면. 시장에서 지금 움직이는 종목을 실시간으로 보여준다.

```
Home
├── 스크리너 (실시간 랭킹)
│   ├── 거래대금순 / 거래량순 / 급상승 / 급하락
│   ├── 국내 / 해외 필터
│   └── 종목별 현재가 · 등락률 · 거래량
├── Quant Lab 신호
│   └── 내 전략에서 오늘 발생한 신호
└── 관심종목 알림
```

### Stock Detail

The most important screen in monticker.

```
Stock Detail
├── 현재가 · 등락률 · 거래량 · 시가총액
├── 실시간 차트 (1m / 5m / 1d)
│   └── 이벤트 마커: 뉴스·공시·거래량급등·감정신호
├── VWAP · RSI · MACD 오버레이
├── 호가창 (KIS 실시간 / Yahoo Finance 15분 / Mock)
├── 이벤트 타임라인
├── 뉴스 패널
├── 알림 설정
└── Paper Trade / Quant Lab 신호 확인
```

---

## Navigation

```
NavBar
├── 스크리너 (Home, /)
├── 백테스팅
├── 포트폴리오
├── 관심종목
└── 알림

Quant Lab (upcoming)
├── /quant-lab
├── /quant-lab/builder
├── /quant-lab/backtest
├── /quant-lab/forward-test
├── /quant-lab/vault
└── /strategy-market

Investment Wallet (upcoming)
├── /wallet              — 돈의 이동 지도
├── /wallet/timeline     — 내 돈 이동 타임라인
├── /wallet/replay       — 주문 리플레이
└── /paper/receipt/{id}  — 투자 영수증
```

---

## MVP Scope

### Done

```
├── 회원가입 / 로그인 (JWT)
├── 종목 검색 + 자동완성
├── 관심종목 (그룹 관리)
├── 실시간 차트 (캔들 1m/1d)
├── 이벤트 타임라인 (가격급등·거래량급증)
├── 호가창 (Yahoo Finance / Mock)
├── 스크리너 (202개 종목, 실시간 랭킹)
├── 알림 설정 (가격·거래량)
├── 백테스팅 (3가지 전략 엔진)
├── 포트폴리오 리스크 지표 (Sharpe·Beta·MDD·VaR)
├── 모의투자 (Paper Trading)
└── VWAP · RSI · MACD 오버레이
```

### Exclude from MVP

```
실제 주문 체결
Quant Lab 룰셋 빌더 UI
Strategy Market
AI 자동 매수/매도
소셜 커뮤니티
가상 투자 미션 / 친구 대결 리그
```

---

## Development Phases

| Phase | Focus |
|-------|-------|
| 1 | Foundation: auth, stock master, watchlist, price API, chart |
| 2 | Core: news, event timeline, volume surge detection |
| 3 | Realtime: WebSocket, Redis, alert engine, screener |
| 4 | AI Insight: news summary, sentiment, event scoring |
| 5 | Paper Trading + Portfolio analytics |
| 6 | **Quant Lab Phase 1**: ruleset builder, watchlist-scoped signals |
| 7 | **Quant Lab Phase 2**: backtest engine, reliability score |
| 8 | **Quant Lab Phase 3**: forward test + mock auto-trading |
| 9 | **Strategy Market**: sharing, badges, ruleset protection |
| 10 | **Investment Wallet Phase 1**: 원장 테이블, 돈의 이동 타임라인, 투자 영수증 |
| 11 | **Investment Wallet Phase 2**: 감정 태그, 주문 리플레이, 투자 행동 점수 |
| 12 | **Investment Wallet Phase 3**: 생존 점수, 가상 투자 미션, 친구 대결 리그 |

---

## Key Design Decisions

1. **Central domain object = `stock_events`**, not `price`.
2. **Quant ruleset = server-side only**. Never serialised to client.
3. **Backtest reliability score** is mandatory. Over-optimised strategies must be flagged before sharing.
4. **Strategy Market ≠ investment advisory**. Positioned as "tool / simulation content", not "stock picks".
5. **Order book provider chain**: KIS realtime → Yahoo Finance (15m delay) → Mock.
6. **원장(ledger) 패턴**: Investment Wallet의 모든 잔고 변경은 이벤트 로그로 기록. 잔고는 이벤트를 replay해서 계산 가능해야 함.
7. **감정 태그 ≠ 투자 조언**: 교육용 피드백으로만 제공. "이 종목을 팔아라" 형태의 추천 금지.
