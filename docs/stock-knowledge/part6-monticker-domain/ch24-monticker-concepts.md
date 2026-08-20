# 24장. monticker 고유 개념

> **핵심 한 줄**: monticker의 도메인 모델은 앞서 배운 금융 개념들을 코드로 구현한 것이다. 개념을 이해하면 코드가 보이고, 코드를 보면 개념이 깊어진다.

---

## 24.1 Wallet (지갑) — 투자 자산의 4가지 상태

사용자의 투자 자산은 항상 아래 4가지 상태 중 하나에 있습니다.

```
총 자산 = 사용 가능 현금 + 주문 예약금 + 보유 주식 평가액 + 정산 대기 금액
```

| 상태 | 설명 | 예시 |
|------|------|------|
| **availableCash** | 즉시 주문에 사용할 수 있는 현금 | 자유롭게 이동 가능 |
| **reservedCash** | 미체결 매수 주문에 묶여 있는 현금 | 주문 취소 시 반환 |
| **holdingsValue** | 현재 보유 주식의 시가 평가액 | 실시간 변동 |
| **settlementPending** | 매도 체결 후 T+2 정산 대기 중인 금액 | 2영업일 후 availableCash로 이동 |

```kotlin
data class WalletSnapshot(
    val availableCash: BigDecimal,
    val reservedCash: BigDecimal,
    val holdingsValue: BigDecimal,
    val settlementPending: BigDecimal,
) {
    val totalAssets: BigDecimal get() =
        availableCash + reservedCash + holdingsValue + settlementPending
}
```

---

## 24.2 LedgerEvent — 원장 이벤트 타입 전체

**원장(Ledger)**: 모든 자산 변동의 불변 기록. 은행의 거래 내역서와 동일 개념.

| 이벤트 타입 | 의미 | 금액 방향 |
|------------|------|----------|
| `DEPOSIT` | 입금 | + |
| `WITHDRAWAL` | 출금 | − |
| `FILL` | 주식 매수/매도 체결 | 매수: −, 매도: + |
| `PARTIAL_FILL` | 부분 체결 | 동일 |
| `CASH_RESERVED` | 매수 주문 예약금 차감 | − (availableCash → reserved) |
| `CASH_UNRESERVED` | 주문 취소로 예약금 반환 | + (reserved → available) |
| `FEE` | 수수료 차감 | − |
| `SETTLEMENT` | 실물 정산 완료 | + (pending → available) |
| `PAPER_SETTLEMENT_COMPLETE` | 모의투자 T+2 정산 완료 | + |
| `SUBSCRIPTION_PAYMENT` | 구독료 결제 | − |
| `CREATOR_EARNING_CREDITED` | 전략 구독 수익 적립 | + |
| `CREATOR_PAYOUT_PAID` | 수익 출금 처리 | − |
| `BROKERAGE_SETTLEMENT` | 실제 증권사 정산 | + 또는 − |

원장 이벤트는 **절대 삭제하거나 수정하지 않습니다**. 오류 시 역방향 이벤트를 추가합니다.

---

## 24.3 PaperAccount — 모의투자 전용 계좌

실제 돈이 아닌 가상 자산으로 주식 거래를 연습하는 계좌.

```kotlin
data class PaperAccount(
    val userId: Long,
    val initialCapital: BigDecimal,     // 초기 시뮬레이션 자본금
    val currentCash: BigDecimal,
    val positions: List<PaperPosition>, // 보유 종목 목록
    val createdAt: LocalDateTime,
)

data class PaperPosition(
    val stockId: Long,
    val quantity: Int,
    val averageCost: BigDecimal,         // 평균 매입가
    val currentValue: BigDecimal,        // 현재 시가 평가액
    val unrealizedPnl: BigDecimal,       // 미실현 손익
)
```

---

## 24.4 Paper Trading vs Real Trading 분리 이유

| 항목 | Paper Trading | Real Trading |
|------|-------------|--------------|
| 자산 | 가상 자산 | 실제 자산 |
| 체결 | 현재 시장 가격 시뮬레이션 | KIS Open API 실제 주문 |
| 정산 | T+2 시뮬레이션 (스케줄러) | 실제 증권사 정산 |
| 리스크 | 없음 | 실손 위험 |
| 목적 | 전략 학습 및 테스트 | 실제 투자 |

monticker의 백엔드는 Paper와 Real을 **완전히 분리된 서비스**로 구현합니다.  
혼용 시 실제 자산에 대한 실수 주문 위험이 있기 때문입니다.

```kotlin
// 의존성 명확히 분리
PaperTradingService → PaperBrokerageClient (Mock)
RealTradingService  → KisBrokerageClient   (실제 API)
```

---

## 24.5 QuantRule / RuleSet — 룰 기반 전략 빌더

사용자가 코드 없이 퀀트 전략을 만들 수 있는 시각적 규칙 빌더.

```
RuleSet = 전략의 이름 + 설명 + 여러 개의 QuantRule

QuantRule = {
  진입 조건들 (AND 연산)
  청산 조건들 (OR 연산)
  포지션 크기 설정
  리스크 관리 (스톱 로스/이익 실현)
}
```

### 조건 표현 예시

```json
{
  "indicator": "RSI",
  "params": {"period": 14},
  "operator": "LESS_THAN",
  "value": 30
}
```

지원 지표: RSI, MACD, MA, EMA, 볼린저 밴드, ATR, 거래량 비율, 가격 변화율 등

---

## 24.6 BacktestResult — 지표 해석 방법

백테스트 결과 객체와 각 지표의 해석.

```kotlin
data class BacktestResult(
    val strategyId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    
    // 수익률
    val totalReturn: Double,           // 총 누적 수익률
    val annualizedReturn: Double,      // 연환산 수익률
    val benchmarkReturn: Double,       // 벤치마크 (KOSPI) 수익률
    val alpha: Double,                 // 초과 수익률
    
    // 리스크
    val volatility: Double,            // 연 변동성
    val maxDrawdown: Double,           // 최대 낙폭 (음수)
    val var95: Double,                 // 95% VaR
    
    // 리스크 조정 수익률
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val calmarRatio: Double,
    
    // 거래 통계
    val totalTrades: Int,
    val winRate: Double,               // 승률
    val profitFactor: Double,          // 손익비
    val avgHoldingDays: Double,        // 평균 보유 기간
)
```

---

## 24.7 StockEvent — 이벤트 감지 파이프라인

주식 시장에서 의미 있는 사건을 자동으로 감지하고 사용자에게 알립니다.

```
가격/거래량 데이터 스트림
        │
        ▼
  EventDetector
  (규칙 기반 감지)
        │
    ┌───┴───┐
    ▼       ▼
급등감지  급락감지
볼린저돌파  거래량급등
52주신고가  상한가근접
공시연동  ...
        │
        ▼
  StockEvent 생성
  (importanceScore 0~100 부여)
        │
        ▼
  사용자 알림 (WebSocket/Push)
```

### ImportanceScore 기준

| 점수 범위 | 의미 |
|----------|------|
| 90~100 | 매우 중요 (상한가, 공시, 대규모 거래량 급등) |
| 70~89 | 중요 (52주 신고가, 거래량 5배) |
| 50~69 | 보통 (RSI 과매도, 볼린저 이탈) |
| 30~49 | 참고 (거래량 2배, 5% 상승) |

---

## 24.8 Fill / PartialFill — 체결 도메인 객체

```kotlin
sealed class FillEvent {
    data class FullFill(
        val orderId: Long,
        val stockId: Long,
        val quantity: Int,
        val price: BigDecimal,
        val filledAt: LocalDateTime,
        val commission: BigDecimal,
    ) : FillEvent()
    
    data class PartialFill(
        val orderId: Long,
        val stockId: Long,
        val filledQuantity: Int,
        val remainingQuantity: Int,
        val price: BigDecimal,
        val filledAt: LocalDateTime,
    ) : FillEvent()
}
```

---

## 24.9 Settlement — 4개 정산 도메인

monticker는 네 가지 서로 다른 정산 흐름을 처리합니다.

| 정산 종류 | 트리거 | 처리 주체 |
|----------|--------|---------|
| **PaperSettlement** | Paper Trading 체결 후 T+2 영업일 | PaperSettlementService |
| **SubscriptionSettlement** | 구독 결제 성공 | SubscriptionService |
| **CreatorSettlement** | 전략 구독자 구독료의 일부 | CreatorEarningService |
| **BrokerageSettlement** | 실제 증권사 체결 후 | BrokerageSettlementService |

---

## 24.10 Subscription Plan — FREE / PRO / QUANT 차이

| 기능 | FREE | PRO | QUANT |
|------|------|-----|-------|
| 스크리너 | 기본 필터 | 전체 필터 | 전체 필터 + API |
| 모의투자 | 1 계좌 | 3 계좌 | 무제한 |
| 백테스트 | 1년 데이터 | 10년 데이터 | 무제한 |
| 전략 공유 | 불가 | 가능 | 가능 + 수익 배분 |
| 실시간 알림 | 5개 | 50개 | 무제한 |
| AI 분석 | 없음 | 기본 | 고급 |
| 월 요금 | 무료 | 9,900원 | 29,900원 |

**QUANT 플랜의 수익 배분**: 다른 사용자가 내 전략을 구독하면 구독료의 일부가 `CREATOR_EARNING_CREDITED` 이벤트로 적립됩니다.

---

## 요약

```
Wallet = availableCash + reservedCash + holdingsValue + settlementPending
LedgerEvent = 모든 자산 변동의 불변 기록
PaperAccount = 가상 자산으로 연습하는 모의투자 계좌
QuantRule = 코드 없이 만드는 규칙 기반 퀀트 전략
BacktestResult = 수익률 + 리스크 + 거래 통계의 종합
StockEvent = 자동 감지된 시장 이벤트 + 중요도 점수
4개 정산 도메인 = 모의/구독/제작자/증권사
```

← [23장](../part5-korea-market/ch23-regulations.md) | → [README (목차로)](../README.md)

---

> **면책 고지**: 이 문서의 모든 내용은 교육 목적이며, 특정 종목의 매수·매도를 권유하거나  
> 수익률을 보장하지 않습니다. 실제 투자는 투자자 본인의 판단과 책임하에 이루어져야 합니다.
