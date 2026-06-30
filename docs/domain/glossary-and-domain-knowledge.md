# 용어집 & 도메인 지식 — 신입 개발자를 위한 안내서

이 문서는 증권/퀀트 도메인이 처음인 개발자가 monticker 코드베이스와 다른 도메인 문서를 읽기 전에 보는 입문서입니다. "이 용어가 코드에서 왜 이렇게 쓰였는지"를 함께 적었고, 더 깊이 알고 싶을 때 찾아볼 외부 링크를 모았습니다.

---

## 1. 주식 시장 기초

### 호가 (Quote / Order Book)

매수자와 매도자가 "이 가격에 사고 싶다/팔고 싶다"고 제시한 가격과 수량의 목록. 매도호가(ask)는 낮은 가격부터, 매수호가(bid)는 높은 가격부터 정렬된다.

```
매도호가 (asks)        매수호가 (bids)
70,200원  30주          70,000원  40주   ← 최우선 매수호가 (best bid)
70,100원  50주  ← 최우선 매도호가 (best ask)
```

최우선 매도호가와 최우선 매수호가 사이의 간격을 **스프레드(spread)**라 한다. monticker에서는 `OrderBook` 클래스(`backend/api/.../matching/application/OrderBook.kt`)가 이를 구현한다. → [matching-engine-clob.md](../technical/matching-engine-clob.md)

### 시장가 주문 (Market Order) / 지정가 주문 (Limit Order)

- **시장가**: "지금 거래되는 가격에 무조건 사겠다/팔겠다." 즉시 체결되지만 정확히 얼마에 체결될지는 보장되지 않는다.
- **지정가**: "이 가격 이하/이상에서만 사겠다/팔겠다." 원하는 가격을 보장하지만 그 가격에 도달하지 않으면 체결되지 않고 대기(호가창에 등록)한다.

### 체결 (Fill / Execution)

매수 주문과 매도 주문이 가격·수량 조건을 만족해 실제 거래가 성사되는 것. 한 번의 주문이 여러 번에 걸쳐 나눠 체결되는 것을 **부분체결(partial fill)**이라 한다.

### 슬리피지 (Slippage)

주문 시점에 기대한 가격과 실제 체결 가격의 차이. 대량 주문이 호가창의 여러 가격대를 연속으로 소진하면서 평균 체결가가 불리해지는 현상이 대표적이다. monticker의 체결 엔진은 이를 시뮬레이션한다. → [matching-engine-clob.md](../technical/matching-engine-clob.md)

### 매수/매도 우선순위 — 가격 우선, 시간 우선

거래소가 체결 순서를 정하는 두 가지 규칙.
1. **가격 우선**: 더 유리한 가격(매수는 더 높은 가격, 매도는 더 낮은 가격)을 제시한 주문이 먼저 체결.
2. **시간 우선**: 같은 가격이면 먼저 주문을 넣은 사람이 먼저 체결 (FIFO, First-In-First-Out).

### 호가 단위 (Tick Size)

가격을 표시할 수 있는 최소 단위. 한국 주식은 가격대별로 다른 호가 단위를 쓴다(예: 1,000원 미만은 1원 단위, 50만원 이상은 1,000원 단위). `MockOrderBookProvider.priceUnit()`에 이 표가 구현되어 있다.

📚 참고: [한국거래소 — 호가 가격 단위](https://www.krx.co.kr)

---

## 2. 캔들·차트 지표

### OHLCV

`Open(시가) / High(고가) / Low(저가) / Close(종가) / Volume(거래량)` — 특정 시간 구간(1분, 1일 등) 동안의 가격 움직임을 요약한 5개 값. "캔들(candle)" 또는 "봉"이라 부른다. monticker는 `candles_1m`(1분봉), `candles_1d`(일봉) 테이블에 저장한다.

### 이동평균 (Moving Average, MA)

최근 N개 캔들의 종가 평균. 가격의 단기 노이즈를 제거하고 추세를 보기 위해 쓴다.
- **SMA(단순이동평균)**: 단순 산술 평균.
- **EMA(지수이동평균)**: 최근 데이터에 더 큰 가중치를 주는 평균. `k = 2/(period+1)`의 가중치로 매번 갱신한다.

### RSI (Relative Strength Index, 상대강도지수)

최근 N일간 상승폭 평균과 하락폭 평균의 비율로 "과매수/과매도" 상태를 0~100 사이로 나타내는 지표. 일반적으로 70 이상은 과매수(곧 떨어질 수 있음), 30 이하는 과매도(곧 오를 수 있음)로 해석한다.

📚 참고: [Investopedia — Relative Strength Index (RSI)](https://www.investopedia.com/terms/r/rsi.asp)

### MACD (Moving Average Convergence Divergence)

단기 EMA와 장기 EMA(보통 12일·26일)의 차이를 "MACD 라인"으로, 그 MACD 라인의 EMA(보통 9일)를 "신호선(signal line)"으로 그려 두 선의 교차로 추세 전환을 포착하는 지표. MACD가 신호선을 아래에서 위로 뚫으면 **골든크로스**(매수 신호로 해석), 위에서 아래로 뚫으면 **데드크로스**(매도 신호로 해석)라 부른다.

📚 참고: [Investopedia — MACD](https://www.investopedia.com/terms/m/macd.asp)

### 볼린저 밴드 (Bollinger Bands)

이동평균을 중심선으로, 그 위아래로 표준편차의 N배(보통 2배)만큼 떨어진 두 선을 그린 밴드. 가격이 상단 밴드를 뚫으면 과열, 하단 밴드를 뚫으면 과매도로 해석하는 경우가 많다.

📚 참고: [Investopedia — Bollinger Bands](https://www.investopedia.com/terms/b/bollingerbands.asp)

### ADX (Average Directional Index, 평균방향지수)

추세의 "강도"를 0~100으로 나타내는 지표 — 방향(상승/하락)은 알려주지 않고 오직 "추세가 뚜렷한가 횡보인가"만 말해준다. 통상 25 이상이면 강한 추세, 20 미만이면 추세 없음(횡보)으로 본다. monticker는 시장 국면 분류에 이를 사용한다. → [quant-analytics-algorithms.md](../technical/quant-analytics-algorithms.md)

📚 참고: [Investopedia — Average Directional Index (ADX)](https://www.investopedia.com/terms/a/adx.asp)

### ZigZag

작은 가격 변동(노이즈)을 무시하고, 일정 비율 이상의 의미 있는 추세 전환점만 골라내는 차트 도구. 차트 패턴(이중바닥, 헤드앤숄더 등)을 인식하는 전처리 단계로 쓰인다.

---

## 3. 매매·포트폴리오 성과 지표

### 수익률 (Return)

투자 결과가 원금 대비 얼마나 늘거나 줄었는지의 비율. `(현재가치 - 투자금) / 투자금 × 100`.

### 연환산 (Annualized)

특정 기간의 수익률을 "1년 기준으로 환산하면 얼마인가"로 변환한 값. 거래일 기준 1년은 보통 **252일**(미국 시장 기준 관행)로 계산한다.

### MDD (Maximum Drawdown, 최대 낙폭)

투자 기간 중 자산이 고점 대비 가장 많이 떨어졌던 비율. "이 전략을 따라 했다면 최악의 경우 얼마나 손실을 견뎌야 했는가"를 보여주는 핵심 리스크 지표다.

```
고점 100 → 저점 70 → 그 후 120으로 회복
MDD = (100 - 70) / 100 = 30%
```

### 승률 (Win Rate)

전체 거래 중 수익이 난 거래의 비율.

### 손익비 (Profit Factor)

`총 이익 합계 ÷ 총 손실 합계`. 1보다 크면 이익이 손실보다 크다는 뜻. 승률이 낮아도 손익비가 충분히 크면(예: 손절은 작게, 익절은 크게) 전체적으로 수익이 날 수 있다 — 그래서 승률만 보면 안 되고 항상 손익비와 함께 봐야 한다.

### Sharpe Ratio (샤프 비율)

수익률을 "감수한 위험(변동성) 대비"로 평가하는 지표. 같은 수익률이라도 변동성이 작을수록 샤프 비율이 높다.

```
Sharpe = (연환산 수익률 - 무위험수익률) / 연환산 변동성
```

📚 참고: [Investopedia — Sharpe Ratio](https://www.investopedia.com/terms/s/sharperatio.asp)

### VaR (Value at Risk, 위험가치)

"95% 신뢰수준에서, 하루 동안 최대 이 정도까지 손실 볼 수 있다"는 통계적 추정치. 예를 들어 "1일 VaR(95%) = 5%"는 "평소라면(95% 확률로) 하루 손실이 5%를 넘지 않을 것"이라는 뜻이다 — 거꾸로 말하면 5%의 확률로는 이보다 더 큰 손실이 날 수 있다는 의미이기도 하다. monticker의 리스크 한도 시스템이 이 개념을 사용한다. → [risk-limit-system.md](../technical/risk-limit-system.md)

📚 참고: [Investopedia — Value at Risk (VaR)](https://www.investopedia.com/terms/v/var.asp)

### 베타 (Beta)

개별 종목·포트폴리오가 시장 전체(예: 코스피 지수) 대비 얼마나 민감하게 움직이는지 나타내는 값. 베타 1이면 시장과 동일하게, 베타 1.5면 시장보다 1.5배 더 크게 움직인다는 뜻이다.

### Kelly Criterion (켈리 공식)

승률과 손익비를 알 때, 장기적으로 자산을 가장 빠르게 늘리면서도 파산하지 않는 "수학적으로 최적인" 베팅 비율을 계산하는 공식.

```
f* = (b·p - q) / b
b = 손익비, p = 승률, q = 1-p (패율)
```

이론적 최적치(Full Kelly)는 추정 오차에 매우 민감해 변동성이 크므로, 실무에서는 그 절반(**Half Kelly**)을 권장값으로 쓰는 것이 일반적이다. monticker도 이 관행을 따른다. → [quant-analytics-algorithms.md](../technical/quant-analytics-algorithms.md)

📚 참고: [Investopedia — Kelly Criterion](https://www.investopedia.com/terms/k/kellycriterion.asp)

---

## 4. 퀀트·백테스트 용어

### 백테스트 (Backtest)

투자 전략(룰셋)을 실제 자금으로 운용하기 전에, 과거 가격 데이터에 적용해 "만약 이 전략을 그때부터 썼다면 어떤 성과가 났을까"를 시뮬레이션하는 것.

### Look-ahead Bias (미래참조 편향)

백테스트에서 가장 흔하고 치명적인 실수. 특정 시점의 매매 신호를 계산할 때 그 시점 이후의 데이터를 실수로 참조하면, 실제로는 불가능했던 "미래를 알고 매매한" 비현실적으로 좋은 결과가 나온다. monticker의 백테스트 엔진은 `history = candles[0..idx]`처럼 인덱스 이후 데이터를 구조적으로 차단해 이를 방지한다.

📚 참고: [Investopedia — Look-Ahead Bias](https://www.investopedia.com/terms/l/lookaheadbias.asp)

### Survivorship Bias (생존자 편향)

백테스트 대상 종목군이 "현재까지 살아남은" 종목들로만 구성되어, 상장폐지된 종목의 손실 사례가 빠지면서 실제보다 낙관적인 결과가 나오는 편향.

### 과최적화 (Overfitting)

백테스트 조건(파라미터)을 과거 데이터에 지나치게 끼워 맞춰서, 그 과거 구간에서는 완벽한 성과가 나오지만 미래(새로운 데이터)에는 전혀 통하지 않는 전략이 되는 현상. "그 시절에만 통했던 우연"을 "진짜 좋은 전략"으로 착각하게 만든다. monticker의 신뢰도 점수(A~D)는 이를 일부 방지하기 위한 장치다. → [quant-lab-positioning.md](./quant-lab-positioning.md)

📚 참고: [Investopedia — Overfitting](https://www.investopedia.com/terms/o/overfitting.asp)

### 포워드 테스트 (Forward Test)

백테스트(과거 데이터)와 달리, 전략을 **지금부터 실시간으로** 모의 운용하며 검증하는 것. 백테스트 성과가 좋아도 실시간 시장에서는 다르게 작동할 수 있어, 실제 운용 전 마지막 검증 단계로 쓰인다.

### 슬리피지/수수료 반영 (Commission & Slippage Modeling)

현실적인 백테스트라면 매수는 실제보다 비싸게, 매도는 실제보다 싸게 체결된다고 가정(슬리피지)하고, 매매 시 증권사 수수료를 차감해야 한다. 이를 빼고 계산하면 실제보다 낙관적인 결과가 나온다.

---

## 5. 자산배분·재무 용어

### 마코위츠 평균-분산 최적화 (Markowitz Mean-Variance Optimization)

해리 마코위츠가 1952년 제시한, "같은 위험이면 더 높은 수익을, 같은 수익이면 더 낮은 위험을" 추구하는 포트폴리오 비중을 수학적으로 찾는 이론. 현대 포트폴리오 이론(MPT, Modern Portfolio Theory)의 시작점이다. monticker의 포트폴리오 최적화 기능이 이를 근사 구현한다. → [quant-analytics-algorithms.md](../technical/quant-analytics-algorithms.md)

📚 참고: [Investopedia — Modern Portfolio Theory (MPT)](https://www.investopedia.com/terms/m/modernportfoliotheory.asp)

### 효율적 프론티어 (Efficient Frontier)

"이 위험 수준에서 얻을 수 있는 최대 기대수익률"의 조합들을 이은 곡선. 이 곡선 위에 있는 포트폴리오는 같은 위험에서 더 나은 비중 조합이 없는 "효율적인" 상태다. 곡선 아래에 있다면 비중을 조정해 개선할 여지가 있다는 뜻이다.

### 공분산 (Covariance) / 상관관계 (Correlation)

두 자산의 가격이 함께 움직이는 정도. 공분산이 크게 양수면 같은 방향으로, 음수면 반대 방향으로 움직이는 경향이 있다는 뜻이다. 분산투자는 서로 공분산이 낮은(또는 음수인) 자산을 섞어야 진짜 효과가 있다 — 같은 업종 종목끼리는 공분산이 높아 분산 효과가 작다.

### 손익통산 / 세금손실수확 (Tax-Loss Harvesting)

평가손실 중인 자산을 매도해 손실을 "실현"시키고, 이를 다른 자산에서 실현된 이익과 상계해 세금을 줄이는 절세 기법. monticker의 세금 최적화 기능이 이 개념을 시뮬레이션한다 (실제 세무 신고용은 아님). → [quant-analytics-user-value.md](./quant-analytics-user-value.md)

📚 참고: [Investopedia — Tax-Loss Harvesting](https://www.investopedia.com/terms/t/taxgainlossharvesting.asp)

---

## 6. 한국 시장 특수 용어

### 코스피 / 코스닥 (KOSPI / KOSDAQ)

한국거래소(KRX)가 운영하는 두 주요 시장. 코스피는 대형·우량주 중심, 코스닥은 중소형·성장주(기술주 등) 중심이다.

### 양도소득세 / 증권거래세

- **양도소득세**: 주식을 팔아 이익이 났을 때 그 차익에 부과되는 세금(국내 상장주식은 대주주 등 일부 요건에서만 과세, 해외주식은 기본 과세).
- **증권거래세**: 주식을 팔 때 이익 여부와 무관하게 거래 금액 자체에 부과되는 세금.

monticker의 세금 최적화 기능은 양도소득세 22%(지방세 포함 가정)를 단순화해 사용한다 — 정확한 세율은 종목·보유기간·대주주 여부에 따라 달라지므로 실제 신고에는 쓸 수 없다.

### 호가창 (Order Book) 한국식 용어

매도호가는 "매도 1~10호가", 매수호가는 "매수 1~10호가"로 부르며, 보통 차트 UI에서는 매도호가가 위, 매수호가가 아래에 표시된다.

### 투자자문업 / 유사투자자문업

- **투자자문업**: 금융위원회에 정식 등록해 특정 고객에게 1:1로 투자 자문을 제공하는 인가 사업.
- **유사투자자문업**: 불특정 다수에게 간행물, 인터넷, 방송 등을 통해 투자 조언을 제공하는 사업으로, 정식 인가 없이 신고만으로 영위할 수 있다. 다만 수익률 보장·허위 광고 등은 금융위원회가 규제 대상으로 점검한다. monticker의 Quant Lab/Strategy Market이 이 경계를 의식해 설계된 이유는 [quant-lab-positioning.md](./quant-lab-positioning.md)에 자세히 설명되어 있다.

📚 참고: [금융위원회 — 유사투자자문업자 관련 안내](https://www.fsc.go.kr)

---

## 7. monticker 고유 도메인 용어

코드베이스에서만 쓰이는, 일반적인 금융 용어가 아닌 monticker 자체의 도메인 개념입니다.

| 용어 | 의미 |
|------|------|
| **stock_events** | 가격급등·거래량급증·뉴스·공시 등 "지금 무슨 일이 일어났는가"를 정규화해 저장하는 중심 테이블. monticker 전체 설계의 출발점. → [product.md](../product.md) |
| **Quant Lab** | 사용자가 코딩 없이 매매 룰셋을 만들고 백테스트·검증하는 기능 영역. |
| **룰셋 (RuleSet)** | 진입/청산 조건과 포지션 사이징을 JSON으로 정의한 매매 전략. |
| **신뢰도 점수 (Reliability Score, A~D)** | 백테스트 결과가 통계적으로 얼마나 믿을 만한지(거래 횟수·검증 기간 기준)를 나타내는 monticker 자체 등급. |
| **Investment Wallet** | "내 돈이 지금 어떤 상태인지"를 보여주는 기능 영역(원장 타임라인, 영수증, 돈의 이동 지도 등). |
| **원장 (Ledger / ledger_events)** | 모든 잔고 변화를 이벤트로 기록하는 테이블. 이벤트 소싱(Event Sourcing) 패턴을 따른다. → [event-sourcing-ledger.md](../technical/event-sourcing-ledger.md) |
| **감정 태그 (Emotion Tag)** | 매수 시점에 사용자가 직접 선택하는 매수 동기(확신/불안/FOMO 등). 나중에 수익률과 연결해 투자 습관을 분석한다. |
| **행동 점수 / 생존 점수 (Behavior Score / Survival Score)** | 각각 "오늘 충동적으로 거래했는가"와 "지금 포트폴리오가 위험한 구조인가"를 평가하는 monticker 자체 지표. |
| **체결 엔진 (Matching Engine)** | 실거래소 방식(CLOB)으로 주문을 매칭하는 모듈. 기존의 "즉시 전량체결" 모의투자와 별개로 존재한다. |
| **리스크 한도 시스템 (Risk Limit System)** | 주문이 체결되기 전에 5가지 규칙(일일손실/집중도/VaR/종목수/거래빈도)으로 사전 차단하는 게이트. |

---

## 8. 더 공부하고 싶다면

| 주제 | 추천 자료 |
|------|----------|
| 기술적 분석 전반 | [Investopedia — Technical Analysis](https://www.investopedia.com/terms/t/technicalanalysis.asp) |
| 현대 포트폴리오 이론 | [Investopedia — Modern Portfolio Theory](https://www.investopedia.com/terms/m/modernportfoliotheory.asp) |
| 백테스트 함정들 | [Investopedia — Backtesting](https://www.investopedia.com/terms/b/backtesting.asp) |
| 거래소 매칭 엔진 개념 | [Investopedia — Order Matching System](https://www.investopedia.com/terms/m/matchingorders.asp) |
| 한국 시장 제도 | [한국거래소(KRX) 공식 사이트](https://www.krx.co.kr) |
| 금융 규제 동향 | [금융위원회(FSC) 공식 사이트](https://www.fsc.go.kr) |
| 행동재무학(투자 심리) | [Investopedia — Behavioral Finance](https://www.investopedia.com/terms/b/behavioralfinance.asp) |

---

## 9. 이 문서들을 읽는 순서 제안

```
1. 이 문서 (glossary-and-domain-knowledge.md)   ← 용어부터 익히기
2. docs/product.md                               ← monticker가 무엇을 만드는 제품인지
3. docs/domain/quant-lab-positioning.md          ← Quant Lab의 "왜"
4. docs/domain/risk-management-trust.md          ← 리스크 관리의 "왜"
5. docs/domain/investment-wallet-ux-philosophy.md
6. docs/domain/quant-analytics-user-value.md
7. docs/technical/                               ← 이제 "어떻게 구현했는지" 코드와 함께 읽기
```

용어가 헷갈릴 때마다 이 문서로 돌아오면 됩니다.
