# 장 시간 처리와 VWAP — 실 증권 도메인 구현

## 개요

Mock 시세 생성기가 24시간 동작하면 실제 시장이 닫혀있는 주말과 야간에도 가격이
변동된다. 이는 시스템의 신뢰성을 떨어뜨리고 알림 오발송의 원인이 된다.
또한 VWAP(Volume Weighted Average Price)는 기관 매매의 기준선으로 활용되는 핵심
지표지만, 종가나 이동평균으로 대체할 수 없는 고유한 의미를 가진다.

---

## 장 시간 처리 (MarketSchedule)

### 한국 시장 구분

```
07:30  시간외 단일가 개시  (PRE_MARKET, volatility × 0.3)
09:00  정규장 개시        (OPEN,       volatility × 1.0)
15:30  정규장 종료        (POST_MARKET, volatility × 0.2)
18:00  장 완전 종료       (CLOSED,     틱 생성 없음)
```

주말 및 공휴일은 `DayOfWeek` 판별로 CLOSED 처리한다.

### 미국 시장 구분 (ET 기준)

```
04:00  프리마켓 개시     (PRE_MARKET, volatility × 0.2)
09:30  정규장 개시       (OPEN,       volatility × 1.0)
16:00  정규장 종료       (POST_MARKET, volatility × 0.15)
20:00  장 완전 종료      (CLOSED)
```

### 설계 원칙

`MarketSchedule`은 순수 정적 메서드(object)로 구현한다.
시간대 변환은 `ZoneId.of("Asia/Seoul")`과 `ZoneId.of("America/New_York")`를 사용해
일광절약시간(DST)을 자동 처리한다.

`MockPriceGenerator.generate()`는 `CLOSED` 상태인 종목을 `mapNotNull`로 스킵한다.
장외 시간에는 `volatilityMultiplier`를 적용해 실제 시장 패턴(장외 거래량 낮음,
변동폭 좁음)을 모사한다.

---

## VWAP (Volume Weighted Average Price)

### 수식

```
VWAP = Σ(가격 × 거래량) / Σ(거래량)
```

분 단위로 계산할 때 각 분봉의 close 가격과 volume을 곱한 값의 합을 전체 거래량으로 나눈다.

### 누적 VWAP 시계열

장 시작부터 현재까지의 누적 VWAP를 계산해 차트에 오버레이한다.

```sql
SELECT
    candle_time,
    SUM(close * volume) OVER (ORDER BY candle_time) AS cum_price_vol,
    SUM(volume)         OVER (ORDER BY candle_time) AS cum_volume
FROM candles_1m
WHERE stock_id = ? AND candle_time >= (당일 00:00)
ORDER BY candle_time
```

윈도우 함수로 O(n) 단일 쿼리에서 계산하며, 애플리케이션 레이어의 반복 계산 없이
DB가 직접 누적값을 반환한다.

### VWAP의 해석

- 현재가 > VWAP: 당일 평균보다 비싸게 거래 중 (매도 압력 가능성)
- 현재가 < VWAP: 당일 평균보다 싸게 거래 중 (저평가 신호 가능성)
- 기관투자자는 VWAP 기준으로 알고리즘 분할 매매를 실행

차트에서는 분홍 점선(`#ff79c6`)으로 표시해 MA5/MA20과 시각적으로 구분한다.

---

## API

```
GET /api/stocks/{stockId}/vwap
→ { stockId, vwap, totalVolume, candleCount, since }

GET /api/stocks/{stockId}/vwap/series
→ [{ time: epochSec, vwap: "71234.5000" }, ...]
```

`GET /api/stocks/**`는 이미 `permitAll`이므로 인증 없이 조회 가능하다.

---

## 지연 측정 (LatencyTracker)

```
tick 생성(generatedAt) → Redis 기록 → DB INSERT → WebSocket 발송
     ↑                        ↑              ↑              ↑
 t=0                     t=redis        t=db          t=broadcast
```

각 구간을 Micrometer `Timer`로 기록하며, 100ms 초과 시 WARN 로그를 발생시킨다.

```
GET /api/latency
→ {
    redisWrite:    { count, p50, p95, p99, mean },
    dbWrite:       { count, p50, p95, p99, mean },
    broadcast:     { count, p50, p95, p99, mean },
    totalPipeline: { count, p50, p95, p99, mean },
  }
```

Micrometer `PercentileHistogramTimer`를 사용해 서버 재시작 없이 누적 통계를 조회할 수 있다.

---

## 한계

- **공휴일**: 한국 공휴일은 정적 데이터로 관리해야 하며 현재 미구현
- **장 외 가격 gap**: 전날 종가 대비 갭업/갭다운 시뮬레이션 없음
- **지연 단절**: Worker → API 간 TraceId 전파 없어 Jaeger에서 단일 트레이스로 확인 불가
- **VWAP 당일 기준**: 자정 UTC 기준으로 초기화 (KST와 9시간 차이 고려 필요)
