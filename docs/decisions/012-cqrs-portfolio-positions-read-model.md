# ADR-012: CQRS Read Model Table for Portfolio Positions

## Status
Accepted

## Context

`PaperPortfolioQueryService.buildHoldings()`는 사용자의 보유 종목을 조회할 때마다 `paper_trades` 전체를 GROUP BY / HAVING으로 집계한다:

```sql
SELECT stock_id,
       SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) AS net_qty,
       SUM(CASE WHEN side='BUY' THEN amount ELSE 0 END) /
       NULLIF(SUM(CASE WHEN side='BUY' THEN quantity ELSE 0 END), 0) AS avg_price
FROM paper_trades
WHERE user_id = ?
GROUP BY stock_id HAVING SUM(...) > 0
```

이 패턴의 문제:

1. **선형 비용 증가**: 거래 횟수가 늘어날수록 집계 비용이 증가한다. 모의투자 사용자가 수천 건의 거래를 쌓으면 조회가 느려진다.
2. **N+1 패턴**: `WalletService.calcHoldingsValue()`는 보유 종목별로 최신가를 개별 조회한다.
3. **읽기 최적화 어려움**: 집계 쿼리에는 인덱스가 효과적으로 적용되지 않는다.

ADR-001에서 DDD + CQRS 방향을 결정했고, Phase 5 CQRS에서 QueryService를 분리했다(서비스 레이어 분리).  
이번 결정은 **DB 레이어에서도** 읽기 모델을 분리하는 것이다.

## Decision

`portfolio_positions` 테이블을 읽기 전용 집계 뷰로 도입한다.

```sql
CREATE TABLE portfolio_positions (
    user_id       BIGINT        PRIMARY KEY 일부,
    stock_id      BIGINT        PRIMARY KEY 일부,
    net_qty       INTEGER,
    avg_buy_price NUMERIC(18,4),
    total_cost    NUMERIC(18,4),   -- avg_buy_price 정확한 갱신을 위한 누적 비용
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (user_id, stock_id)
);
```

`PortfolioPositionProjection`이 `PaperTradingService`의 BUY/SELL/reset 직후 동기 업데이트한다 (동일 트랜잭션).

읽기 측:
- `buildHoldings()` → `portfolio_positions WHERE user_id = ?` (PK 조회)
- `calcHoldingsValue()` → `portfolio_positions + LATERAL JOIN candles_1m` (단일 쿼리)

## Reasons

### 동기 업데이트 vs. 비동기 프로젝션

비동기 이벤트 기반 프로젝션은 최종 일관성을 도입해 조회 직후 갱신된 포지션이 보이지 않을 수 있다.  
모의투자 UX는 거래 직후 포지션이 즉시 반영되어야 하므로 **동기 업데이트**를 선택했다.

동일 트랜잭션에서 `paper_trades`와 `portfolio_positions`를 함께 갱신하므로 항상 일관성이 유지된다.  
트랜잭션 롤백 시 두 테이블 모두 롤백된다.

### `total_cost` 컬럼 유지

`avg_buy_price`만 저장하면 추가 매수 시 재계산에 오차가 누적된다.

```
1차 매수: 100주 @ 1,000원 → avg = 1,000
2차 매수:  50주 @ 1,200원 → avg = (100×1,000 + 50×1,200) / 150 = 1,066.67
```

이를 계산하려면 `total_cost`(1차 구매액 + 2차 구매액)가 필요하다.  
`total_cost` 없이 `avg_buy_price`만으로는 2차 매수 전 원가를 역산할 수 없다.

### LATERAL JOIN

`calcHoldingsValue()`의 N+1 문제는 PostgreSQL `LATERAL JOIN`으로 해결한다:

```sql
SELECT COALESCE(SUM(pp.net_qty * c.close), 0)
FROM portfolio_positions pp
JOIN LATERAL (
    SELECT close FROM candles_1m
    WHERE stock_id = pp.stock_id
    ORDER BY candle_time DESC LIMIT 1
) c ON TRUE
WHERE pp.user_id = ? AND pp.net_qty > 0
```

각 포지션 행에 대해 최신 가격을 서브쿼리로 조회하고 합산을 단일 쿼리로 완료한다.

## Consequences

- **쓰기 경로 복잡성 증가**: 모든 거래에서 추가 UPDATE/UPSERT가 필요하다. 트랜잭션 시간이 약간 늘어난다.
- **초기화 필요**: 기존 `paper_trades` 데이터가 있는 경우 배포 시 백필 SQL을 실행해야 한다.
- **`paper_trades`와 `portfolio_positions` 이중 관리**: 버그 또는 DB 직접 조작으로 두 테이블이 불일치할 수 있다. 주기적 일관성 검증이 필요하다.
- **`MaterializedView` 대비**: PostgreSQL Materialized View도 유사한 역할을 하지만, 갱신 시점 제어(`REFRESH MATERIALIZED VIEW`)가 필요하고 트랜잭션과 통합되지 않는다. 애플리케이션 레이어 관리가 더 유연하다.

## Revisit When

- 읽기 요청이 크게 증가해 `portfolio_positions` 조회도 병목이 될 때 → Redis 캐시(`portfolio:snapshot:{userId}`, TTL 30초)를 추가한다.
- 쓰기 빈도가 크게 증가해 동기 업데이트가 거래 지연을 유발할 때 → 비동기 프로젝션(Kafka 이벤트 기반)으로 전환하고 최종 일관성을 허용한다.
- `paper_trades` 집계가 아닌 다른 집계(월별 수익률 등)도 읽기모델로 분리할 필요가 생길 때 → 동일 패턴을 확장한다.
