# CQRS 읽기모델 — portfolio_positions

## 배경

`PaperPortfolioQueryService.buildHoldings()`는 보유 종목 목록을 조회할 때마다  
`paper_trades` 전체 내역을 GROUP BY / HAVING으로 집계했다:

```sql
SELECT stock_id,
       SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) AS net_qty,
       SUM(CASE WHEN side='BUY' THEN amount ELSE 0 END) /
       NULLIF(SUM(CASE WHEN side='BUY' THEN quantity ELSE 0 END), 0) AS avg_price
FROM paper_trades
WHERE user_id = ?
GROUP BY stock_id
HAVING SUM(...) > 0
```

거래 건수가 늘어날수록 집계 비용이 선형 증가한다.  
또한 `WalletService.calcHoldingsValue()`는 종목별로 현재가를 N번 개별 조회했다 (N+1 패턴).

---

## 설계

**CQRS (Command Query Responsibility Segregation)** 원칙에 따라  
쓰기 모델(`paper_trades`)과 읽기 모델(`portfolio_positions`)을 분리한다.

```
쓰기 측 (Command)          읽기 측 (Query)
────────────────           ──────────────
PaperTradingService         PaperPortfolioQueryService
  .buy()                      .buildHoldings()
  .sell()                       ── SELECT FROM portfolio_positions
  .reset()                         WHERE user_id = ?
      │
      ▼
  PortfolioPositionProjection  ← 동기 업데이트
  (BUY / SELL / reset 직후 호출)
```

### 일관성 모델

**동기 업데이트**: `PaperTradingService`가 `PaperTrade`를 저장한 직후, 같은 트랜잭션에서 `PortfolioPositionProjection`을 호출한다.

이벤트 소싱이나 비동기 프로젝션이 아닌 **동기 모델**을 선택한 이유:
- 조회 측이 항상 최신 상태를 읽어야 함 (모의투자 UX)
- 서비스 규모가 단일 DB 트랜잭션 내 처리로 충분한 수준

---

## portfolio_positions 테이블

```sql
CREATE TABLE portfolio_positions (
    user_id       BIGINT         NOT NULL REFERENCES users(id),
    stock_id      BIGINT         NOT NULL REFERENCES stocks(id),
    net_qty       INTEGER        NOT NULL DEFAULT 0 CHECK (net_qty >= 0),
    avg_buy_price NUMERIC(18,4)  NOT NULL DEFAULT 0,
    total_cost    NUMERIC(18,4)  NOT NULL DEFAULT 0,  -- avg_buy_price 갱신에 사용
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, stock_id)
);
```

`total_cost`를 별도 컬럼으로 유지하는 이유:  
추가 매수 시 `avg_buy_price = (total_cost + new_amount) / (net_qty + new_qty)`로 정확히 계산하기 위해.  
`avg_buy_price`만 저장하면 재계산 시 오차 누적 가능.

---

## PortfolioPositionProjection 구현

### BUY

```kotlin
fun onBuy(userId: Long, stockId: Long, quantity: Int, amount: BigDecimal) {
    jdbc.update("""
        INSERT INTO portfolio_positions (user_id, stock_id, net_qty, avg_buy_price, total_cost, updated_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (user_id, stock_id) DO UPDATE
          SET net_qty       = portfolio_positions.net_qty + EXCLUDED.net_qty,
              total_cost    = portfolio_positions.total_cost + EXCLUDED.total_cost,
              avg_buy_price = (portfolio_positions.total_cost + EXCLUDED.total_cost)
                              / (portfolio_positions.net_qty + EXCLUDED.net_qty),
              updated_at    = now()
    """, userId, stockId, quantity, amount / quantity, amount)
}
```

### SELL

```kotlin
fun onSell(userId: Long, stockId: Long, quantity: Int) {
    val (netQty, avgPrice) = getCurrentPosition(userId, stockId)
    val newQty = (netQty - quantity).coerceAtLeast(0)

    if (newQty == 0) {
        jdbc.update("DELETE FROM portfolio_positions WHERE user_id = ? AND stock_id = ?", userId, stockId)
    } else {
        jdbc.update("""
            UPDATE portfolio_positions
               SET net_qty    = ?,
                   total_cost = GREATEST(total_cost - ?, 0),
                   updated_at = now()
             WHERE user_id = ? AND stock_id = ?
        """, newQty, avgPrice * quantity, userId, stockId)
    }
}
```

`net_qty = 0` 이 되면 행을 삭제한다. `buildHoldings()`가 `net_qty > 0` 조건으로 조회하므로  
삭제하지 않아도 결과는 같지만, 누적 행이 쌓이는 것을 방지한다.

### Reset

```kotlin
fun onReset(userId: Long) {
    jdbc.update("DELETE FROM portfolio_positions WHERE user_id = ?", userId)
}
```

---

## 조회 측 변경

### PaperPortfolioQueryService.buildHoldings() — 이전/이후

```kotlin
// 이전: paper_trades GROUP BY
val rows = tradeRepo.findHoldings(userId)  // JPQL 집계 쿼리

// 이후: portfolio_positions 직접 조회
val positions = jdbc.query(
    "SELECT stock_id, net_qty, avg_buy_price FROM portfolio_positions WHERE user_id = ? AND net_qty > 0",
    { rs, _ -> Triple(rs.getLong("stock_id"), rs.getInt("net_qty"), rs.getBigDecimal("avg_buy_price")) },
    userId,
)
```

### WalletService.calcHoldingsValue() — 이전/이후

```kotlin
// 이전: N+1 패턴 (종목별 개별 가격 조회)
for (row in rows) {
    val price = jdbc.queryForObject("SELECT close FROM candles_1m WHERE stock_id = ? ...", ...)
    total += price * qty
}

// 이후: LATERAL JOIN 단일 쿼리
jdbc.queryForObject("""
    SELECT COALESCE(SUM(pp.net_qty * c.close), 0)
    FROM portfolio_positions pp
    JOIN LATERAL (
        SELECT close FROM candles_1m
        WHERE stock_id = pp.stock_id
        ORDER BY candle_time DESC LIMIT 1
    ) c ON TRUE
    WHERE pp.user_id = ? AND pp.net_qty > 0
""", BigDecimal::class.java, userId)
```

`LATERAL JOIN`은 각 포지션 행에 대해 최신 가격을 서브쿼리로 조회하는 PostgreSQL 기능이다.  
`candles_1m (stock_id, candle_time)` 복합 인덱스로 최신가 조회가 인덱스 스캔으로 처리된다.

---

## 성능 비교

| 항목 | 이전 | 이후 |
|------|------|------|
| `buildHoldings` 쿼리 | `paper_trades` 전체 스캔 + GROUP BY | `portfolio_positions` PK 조회 |
| `calcHoldingsValue` 쿼리 수 | 1 + N(보유 종목 수) | 1 (LATERAL JOIN) |
| 거래 누적 영향 | 선형 증가 | 없음 (포지션 수만 의존) |

---

## 데이터 정합성 보장

### 쓰기 순서

```
PaperTradingService.buy()
  1. PaperAccount debit  (UPDATE paper_accounts)
  2. PaperTrade INSERT   (INSERT INTO paper_trades)
  3. Projection onBuy()  (UPSERT portfolio_positions)  ← 동일 트랜잭션
  4. LedgerEvent INSERT
```

트랜잭션 롤백 시 1~4 모두 롤백. `portfolio_positions`가 `paper_trades`와 항상 일치.

### 초기화 (기존 데이터)

기존 `paper_trades` 데이터가 있는 경우 `portfolio_positions`에 반영되지 않을 수 있다.  
배포 시 다음 SQL로 백필:

```sql
INSERT INTO portfolio_positions (user_id, stock_id, net_qty, avg_buy_price, total_cost, updated_at)
SELECT
    user_id,
    stock_id,
    SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) AS net_qty,
    SUM(CASE WHEN side='BUY' THEN amount ELSE 0 END)
      / NULLIF(SUM(CASE WHEN side='BUY' THEN quantity ELSE 0 END), 0) AS avg_buy_price,
    SUM(CASE WHEN side='BUY' THEN amount ELSE 0 END) AS total_cost,
    now()
FROM paper_trades
GROUP BY user_id, stock_id
HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0
ON CONFLICT (user_id, stock_id) DO NOTHING;
```

---

## 관련 문서

- [ddd-domain-driven-design.md](./ddd-domain-driven-design.md) — CQRS 서비스 분리 전체 로드맵
- [paper-trading.md](./paper-trading.md) — 모의투자 시스템
- [event-sourcing-ledger.md](./event-sourcing-ledger.md) — 원장 이벤트 소싱
