# 모의투자 시스템 설계 — 제로 리스크 투자 경험

## 1. 목적

모의투자는 실제 자금 없이 주식 거래를 경험하는 환경이다. 다음 세 가지 목적을 위해 존재한다.

- **실습 환경**: 처음 투자를 배우는 사용자가 손실 없이 매수/매도를 연습한다.
- **전략 검증**: 백테스팅이 과거 데이터로 전략을 검증한다면, 모의투자는 현재 시장에서 전략을 실시간으로 테스트한다.
- **리스크 없는 학습**: 계좌 초기화 기능으로 언제든 다시 시작할 수 있어 심리적 부담 없이 다양한 전략을 시도할 수 있다.

---

## 2. 계좌 모델

모의투자 계좌는 1인 1계좌 원칙으로 운영된다. 처음 접근 시 자동 생성되며 초기 잔고는 1천만 원이다.

**paper_accounts 테이블 핵심 컬럼**:
- `user_id`: 유일 제약(UNIQUE). 한 사용자는 계좌가 하나만 존재한다.
- `cash`: 현재 현금 잔고 (DECIMAL). 매수 시 감소, 매도 시 증가한다.
- `updated_at`: 최근 거래 시각.

```kotlin
private fun getOrCreateAccount(userId: Long): PaperAccount =
    accountRepo.findByUserId(userId).orElseGet {
        accountRepo.save(PaperAccount(userId = userId))
    }
```

**paper_trades 테이블**: 거래 내역의 원장이다. 포지션 계산이나 성과 분석 모두 이 테이블을 집계하여 도출한다. 수정은 없고 삽입만 존재한다(이벤트 소싱과 유사한 구조).

| 컬럼 | 설명 |
|------|------|
| `user_id` | 거래한 사용자 |
| `stock_id` | 거래 종목 |
| `side` | `BUY` 또는 `SELL` |
| `quantity` | 거래 수량 |
| `price` | 체결 가격 |
| `amount` | `price × quantity` |
| `traded_at` | 거래 시각 |

---

## 3. 체결 방식

체결 가격은 `candles_1m` 테이블의 최신 close 가격을 사용한다.

```kotlin
private fun getCurrentPrice(stockId: Long): BigDecimal =
    jdbc.queryForObject(
        "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
        BigDecimal::class.java, stockId
    ) ?: throw IllegalStateException("현재가 조회 불가: stockId=$stockId")
```

**시장가 즉시 체결**: 사용자가 주문하면 해당 시점의 최신 1분봉 close에 즉시 체결된다. 호가창이 없고 슬리피지가 없다. 이는 시뮬레이션의 단순화이며 실제 거래와의 가장 큰 차이점이다.

---

## 4. 보유 종목 계산

보유 종목과 평균 매수 단가는 `paper_trades`를 집계하여 계산한다. 별도 포지션 테이블은 없다.

```kotlin
@Query("""
    SELECT t.stockId,
           SUM(CASE WHEN t.side='BUY' THEN t.quantity ELSE -t.quantity END) AS qty,
           SUM(CASE WHEN t.side='BUY' THEN t.amount ELSE 0 END) /
           NULLIF(SUM(CASE WHEN t.side='BUY' THEN t.quantity ELSE 0 END), 0) AS avgPrice
    FROM PaperTrade t WHERE t.userId = :userId
    GROUP BY t.stockId
    HAVING SUM(CASE WHEN t.side='BUY' THEN t.quantity ELSE -t.quantity END) > 0
""")
fun findHoldings(@Param("userId") userId: Long): List<Array<Any>>
```

쿼리 설계 포인트:
- `qty`: 매수 수량 합계에서 매도 수량 합계를 뺀다. `HAVING qty > 0`으로 완전 청산된 종목을 제외한다.
- `avgPrice`: 총 매수 금액 ÷ 총 매수 수량. 매도 거래는 분자·분모 모두 제외하여 순수 매입 단가를 유지한다. `NULLIF(..., 0)`은 매수 수량이 0일 때 나누기 오류를 방지한다.

이 방식의 장점은 거래 내역을 수정하거나 포지션 테이블을 별도로 관리할 필요가 없다는 점이다. 단, 거래 건수가 많아지면 집계 쿼리 성능이 저하될 수 있다.

---

## 5. TradeModal UX 설계

매수/매도 주문 화면은 다음 순서로 사용자에게 정보를 제공한다.

1. **잔고 확인**: 현재 보유 현금(매수) 또는 보유 수량(매도) 표시
2. **최대 수량 계산**: 매수 시 `floor(cash / currentPrice)`, 매도 시 `holdingQuantity`
3. **주문 금액 미리보기**: 수량 입력 시 `수량 × 현재가`를 실시간으로 계산하여 표시
4. **확정**: 서버에 주문 전송 후 응답 확인

수량 입력 필드는 슬라이더와 직접 입력을 병행 제공한다. 슬라이더를 최대로 당기면 최대 매수 가능 수량이 자동 입력된다.

---

## 6. TanStack Query Mutation 설계 — 낙관적 업데이트 없이 서버 확인

모의투자 주문은 낙관적 업데이트(optimistic update)를 사용하지 않는다. 주문 전송 후 서버 응답을 기다린 뒤 캐시를 무효화한다.

```typescript
const buyMutation = useMutation({
  mutationFn: (req: BuyRequest) => paperApi.buy(req),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['portfolio'] });
    queryClient.invalidateQueries({ queryKey: ['paperHistory'] });
  },
});
```

**낙관적 업데이트를 사용하지 않는 이유**:

1. **잔고 부족 오류**: 서버에서 `account.cash < amount`를 검증한다. 클라이언트가 미리 잔고를 차감했다가 오류 응답 시 롤백하는 것보다 서버 확인 후 반영이 더 단순하다.

2. **평균 단가 계산**: `avgPrice`는 서버의 JPQL 집계로 계산된다. 클라이언트에서 동일 계산을 구현하는 것은 중복이며 버그 가능성이 있다.

3. **실제 체결가**: 현재가는 요청 시점의 최신 1분봉 close다. 클라이언트에서 표시하는 가격과 실제 체결가가 다를 수 있으므로, 서버 응답의 체결 가격을 신뢰한다.

주문 확정 후 `invalidateQueries`로 포트폴리오와 거래 내역 쿼리를 재조회하면 UI가 서버 상태와 동기화된다.

---

## 7. 백테스팅과의 차이

| 항목 | 백테스팅 | 모의투자 |
|------|----------|----------|
| 가격 기준 | 과거 일봉 close | 최신 1분봉 close |
| 시간 흐름 | 시뮬레이션 (즉시) | 실시간 (거래시간 의존) |
| 신호 발생 | 자동 (전략 알고리즘) | 사용자 수동 결정 |
| 손절/익절 | 자동 실행 | 사용자가 직접 매도 |
| 복수 시나리오 | 동일 기간 여러 전략 비교 가능 | 한 계좌에 순차 거래만 가능 |

백테스팅은 "전략이 과거에 통했는가"를 물어보고, 모의투자는 "지금 내 판단이 맞는가"를 실험한다.

---

## 8. 한계

**슬리피지 미반영**: 실제 주문은 현재가에 즉시 체결되지 않는다. 대량 주문이거나 유동성이 낮은 종목은 요청 가격보다 불리하게 체결된다.

**거래세 없음**: 주식 매도 시 부과되는 증권거래세(0.18%)가 반영되지 않는다. 단타 전략의 실제 수익률은 거래세로 인해 모의투자 결과보다 낮을 수 있다.

**배당 미반영**: 보유 기간 중 발생하는 배당금이 계좌에 입금되지 않는다. 배당주 투자 전략은 실제보다 수익률이 낮게 평가된다.

**장 마감 후 주문**: 거래 시간 외에도 주문이 가능하다. 체결 가격은 마지막으로 기록된 1분봉 close이므로, 장 마감 후 주문과 장 시작 전 주문의 체결가가 실제와 다르다.

**단일 계좌**: 전략별로 별도 계좌를 운영할 수 없다. 여러 전략을 동시에 테스트하려면 계좌를 초기화해야 한다.
