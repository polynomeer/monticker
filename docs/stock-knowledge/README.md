# 주식 도메인 지식 백과

> monticker 개발자와 투자자를 위한 주식 용어 및 도메인 지식 전집  
> 기초 개념부터 퀀트 전략, 한국 시장 특수 사항, monticker 고유 도메인까지 다룹니다.

---

## 목차

### 1부 — 시장 구조의 기초
| 장 | 제목 | 핵심 키워드 |
|----|------|------------|
| [1장](part1-market-structure/ch01-what-is-stock.md) | 주식이란 무엇인가 | 소유권, 시가총액, IPO, 보통주/우선주 |
| [2장](part1-market-structure/ch02-market-structure.md) | 주식 시장의 구조 | KRX, KOSPI, T+2, 서킷브레이커 |
| [3장](part1-market-structure/ch03-order-types.md) | 주문의 종류 | 시장가, 지정가, IOC, FOK, 스톱 주문 |
| [4장](part1-market-structure/ch04-order-book.md) | 호가창 심층 | CLOB, Bid/Ask, Spread, 체결 우선순위 |
| [5장](part1-market-structure/ch05-fill-settlement.md) | 체결과 청산 | Fill, 슬리피지, T+2 결제, 증거금 |

### 2부 — 가격과 차트 분석
| 장 | 제목 | 핵심 키워드 |
|----|------|------------|
| [6장](part2-price-analysis/ch06-candle-ohlcv.md) | 캔들과 OHLCV | OHLCV, 양봉/음봉, 갭, 거래량 |
| [7장](part2-price-analysis/ch07-trend-indicators.md) | 추세 지표 | SMA/EMA, MACD, 골든/데드 크로스 |
| [8장](part2-price-analysis/ch08-momentum-indicators.md) | 모멘텀 지표 | RSI, Stochastic, CCI, ROC |
| [9장](part2-price-analysis/ch09-volatility-indicators.md) | 변동성 지표 | 볼린저 밴드, ATR, IV, VIX |
| [10장](part2-price-analysis/ch10-volume-indicators.md) | 거래량 지표 | OBV, VWAP, MFI |
| [11장](part2-price-analysis/ch11-support-resistance.md) | 지지/저항과 피봇 | 피봇 포인트, 피보나치, ZigZag |

### 3부 — 포트폴리오와 리스크 관리
| 장 | 제목 | 핵심 키워드 |
|----|------|------------|
| [12장](part3-portfolio-risk/ch12-return-calculation.md) | 수익률 계산과 해석 | 단순/로그 수익률, CAGR, Alpha |
| [13장](part3-portfolio-risk/ch13-risk-metrics.md) | 리스크 지표 | MDD, VaR, CVaR, Beta, 상관계수 |
| [14장](part3-portfolio-risk/ch14-performance-metrics.md) | 성과 측정 지표 | Sharpe, Sortino, Calmar, 승률, 손익비 |
| [15장](part3-portfolio-risk/ch15-position-sizing.md) | 포지션 사이징 | Kelly, Half-Kelly, ATR 단위 |
| [16장](part3-portfolio-risk/ch16-asset-allocation.md) | 자산 배분 | 마코위츠, 효율적 프론티어, 리밸런싱 |

### 4부 — 퀀트 투자와 백테스팅
| 장 | 제목 | 핵심 키워드 |
|----|------|------------|
| [17장](part4-quant-backtest/ch17-factor-investing.md) | 팩터 투자 | 가치/모멘텀/품질/저변동성 팩터 |
| [18장](part4-quant-backtest/ch18-screening.md) | 스크리닝 | 재무/기술적 필터, 급등 감지 |
| [19장](part4-quant-backtest/ch19-backtesting.md) | 백테스팅 | Look-ahead Bias, 과최적화, Walk-forward |
| [20장](part4-quant-backtest/ch20-quant-strategies.md) | 퀀트 전략 유형 | 추세추종, 평균회귀, 페어 트레이딩 |

### 5부 — 한국 시장·제도 특수 용어
| 장 | 제목 | 핵심 키워드 |
|----|------|------------|
| [21장](part5-korea-market/ch21-korea-market-structure.md) | 한국 증시 구조 | KOSPI/KOSDAQ, 상한가/하한가, 공시 |
| [22장](part5-korea-market/ch22-tax-and-cost.md) | 세금과 비용 | 증권거래세, 양도소득세, 수수료 |
| [23장](part5-korea-market/ch23-regulations.md) | 투자 규제와 자문 | 투자자문업, 금소법, 유사투자자문 |

### 6부 — monticker 도메인 매핑
| 장 | 제목 | 핵심 키워드 |
|----|------|------------|
| [24장](part6-monticker-domain/ch24-monticker-concepts.md) | monticker 고유 개념 | Wallet, LedgerEvent, QuantRule, Settlement |

---

## 이 문서를 읽는 순서 제안

| 독자 유형 | 권장 경로 |
|----------|----------|
| **투자 완전 초보** | 1장 → 2장 → 6장 → 3장 → 4장 → 5장 → 21장 → 22장 |
| **차트 분석 입문** | 6장 → 7장 → 8장 → 9장 → 10장 → 11장 |
| **퀀트 입문 개발자** | 17장 → 19장 → 20장 → 18장 → 15장 |
| **monticker 개발자** | 24장 → 2장(T+2) → 5장(체결) → 21장 → 22장 |
| **리스크 관리 학습** | 12장 → 13장 → 14장 → 15장 → 16장 |

---

> **면책 고지**: 이 문서의 모든 내용은 교육 목적입니다.  
> 특정 종목의 매수·매도를 권유하지 않으며, 실제 투자 조언이 아닙니다.
