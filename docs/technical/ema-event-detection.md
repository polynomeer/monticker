# EMA 기반 이벤트 감지 시스템

## 개요

monticker의 이벤트 감지 시스템은 주식 가격 및 거래량 틱 데이터를 실시간으로 분석하여 시장에서 비정상적인 움직임이 발생했을 때 `stock_events` 테이블에 이벤트 레코드를 생성한다. 이 문서는 `VolumeSurgeDetector`, `PriceSpikeDetector`, `StockEventWriter` 세 컴포넌트의 설계 결정, 알고리즘, 그리고 제약 사항을 설명한다.

### 해결하는 문제

"거래량이 많다" 또는 "가격이 많이 움직였다"는 판단은 종목마다 다르다. 삼성전자의 일반적인 일중 거래량과 소형주의 그것은 수백 배 차이가 난다. 절대 수치 기반 임계값은 모든 종목에 동일하게 적용할 수 없으며, 사람이 종목마다 임계값을 관리하는 것은 운영 부담이 크고 시장 환경 변화에 취약하다.

이 시스템은 각 종목의 **역사적 기준선을 자동으로 학습**하여, 기준선 대비 상대적인 이탈 폭을 판단 기준으로 삼는다.

---

## 설계 원칙

### 왜 고정 임계값이 아닌 EMA인가

고정 임계값 방식의 근본적인 문제는 종목 간 이질성과 시간적 비정상성이다.

- **종목 간 이질성**: 거래량 1만 주는 어떤 종목에서는 평범하고, 다른 종목에서는 폭발적이다. 단일 임계값은 종목 전체에 걸쳐 false positive 또는 false negative를 양산한다.
- **시간적 비정상성**: 같은 종목도 장 초반, 장 중, 장 마감 전 거래 패턴이 다르다. 실적 발표 시즌에는 변동성 자체가 높아진다.

EMA(지수이동평균)는 이 두 문제를 모두 완화한다. EMA는 과거 모든 관측값의 가중 평균으로, 최근 값에 더 높은 가중치를 부여한다. 결과적으로 각 종목의 "현재 시점 기준 정상 범위"를 추적하며, 시장 환경이 변하면 기준선도 서서히 이동한다.

추가로 EMA는 단일 부동소수점 값 하나만 Redis에 저장하면 되므로 메모리와 연산 비용이 극히 낮다. 20일치 같은 시간대 거래량 히스토리를 종목 수만큼 보관하는 방식에 비해 운영 복잡도가 현저히 낮다.

---

## EMA 수식 및 파라미터 선택

### 수식

```
EMA_t = α × x_t + (1 − α) × EMA_{t−1}
```

- `x_t`: 현재 틱의 관측값 (거래량 또는 가격 변화율)
- `EMA_{t-1}`: 직전 EMA 값
- `α`: 평활 계수 (smoothing factor), 현재 구현에서 `0.1`

### α = 0.1 의 의미

α는 새로운 관측값에 부여하는 가중치다. `α = 0.1`은 현재 틱이 전체 EMA에 10%만 기여한다는 의미이며, 과거 90%의 누적 경험이 유지된다.

**수렴 속도** 측면에서 EMA의 "유효 윈도우 크기"는 대략 `(2 / α) − 1`로 추정된다. `α = 0.1`이면 유효 윈도우는 약 19틱이다. 즉, 초기화 후 약 19회의 관측이 누적되면 EMA가 실질적인 기준선으로 기능하기 시작한다.

```
α = 0.2  → 유효 윈도우 ≈  9틱 (빠른 반응, 노이즈에 취약)
α = 0.1  → 유효 윈도우 ≈ 19틱 (균형)
α = 0.05 → 유효 윈도우 ≈ 39틱 (느린 반응, 이상값에 강건)
```

현재 선택값 `0.1`은 급격한 시장 변화에 적당히 반응하면서도, 단발성 노이즈에 EMA가 크게 흔들리지 않도록 균형을 맞춘 값이다.

### EMA 저장 위치

Redis에 종목 심볼을 키로 저장한다.

| 감지기 | Redis 키 패턴 | 저장 값 |
|---|---|---|
| VolumeSurgeDetector | `detector:volume:ema:{symbol}` | 거래량 EMA (double) |
| PriceSpikeDetector | `detector:price:ema:{symbol}` | 가격 변화율(%) EMA (double) |
| PriceSpikeDetector | `detector:price:prev:{symbol}` | 직전 가격 (BigDecimal 문자열) |

---

## 감지기별 로직

### VolumeSurgeDetector

거래량 급증을 감지한다. 현재 틱의 거래량을 EMA 기준선과 비교하여 배율을 계산한다.

```
ratio = currentVolume / EMA
```

**처리 흐름:**

1. Redis에서 해당 종목의 거래량 EMA를 조회한다.
2. EMA가 없으면 (첫 관측) 현재 거래량으로 초기화하고 종료한다.
3. `ratio`를 계산한다.
4. EMA를 갱신한다 (`α = 0.1`).
5. `ratio < 3.0`이면 이벤트를 생성하지 않고 종료한다.
6. `ratio`에 따라 `importanceScore`를 결정한다.

```
ratio >= 5.0  →  importanceScore = 85  (강한 신호)
ratio >= 3.0  →  importanceScore = 60  (유의미한 신호)
```

**중요한 설계 결정:** EMA 갱신은 임계값 체크 이전에 수행된다. 급등 틱이 EMA에 반영되어야 다음 틱부터 새로운 기준선이 적용된다. 만약 이벤트가 발생한 틱을 EMA에서 제외하면, 연속 급등 시 매 틱마다 이벤트가 발생하는 문제가 생긴다.

---

### PriceSpikeDetector

가격의 단기 급변을 감지한다. 절대 가격이 아닌 **직전 틱 대비 변화율(%)**을 EMA와 비교한다.

```
changePct = |currentPrice - prevPrice| / prevPrice × 100
ratio = changePct / EMA(changePct)
```

**처리 흐름:**

1. Redis에서 직전 가격을 조회한다.
2. 직전 가격이 없으면 현재 가격을 저장하고 종료한다.
3. 변화율 `changePct`를 계산한다 (`BigDecimal` 사용, `HALF_UP` 반올림).
4. 직전 가격을 현재 가격으로 갱신한다.
5. 변화율의 EMA를 조회한다.
6. 변화율 EMA가 없으면 초기화하고 종료한다.
7. `ratio`를 계산한다. EMA가 `0.001` 미만이면 `ratio = 0.0`으로 처리한다 (제로 나눗셈 방지).
8. EMA를 갱신한다.
9. `ratio < 3.0`이면 종료한다.
10. 방향에 따라 이벤트 타입을 결정한다.

```kotlin
if (isSpike) DetectedEventType.PRICE_SPIKE else DetectedEventType.PRICE_DROP
```

```
ratio >= 5.0  →  importanceScore = 80
ratio >= 3.0  →  importanceScore = 55
```

**변화율 EMA를 사용하는 이유:** 가격 자체의 EMA가 아닌 변화율의 EMA를 사용함으로써, 종목의 절대 주가 수준에 무관하게 동일한 로직이 작동한다. 5만원짜리 주식과 50만원짜리 주식에서 "평소보다 5배 큰 가격 변동"을 동일한 기준으로 감지할 수 있다.

---

### StockEventWriter

`VolumeSurgeDetector`와 `PriceSpikeDetector`가 공유하는 이벤트 영속화 컴포넌트다. 두 가지 역할을 수행한다: **분 단위 중복 검사**와 **DB 삽입**.

```kotlin
fun write(event: DetectedEvent): Boolean
```

반환값 `true`는 이벤트가 실제로 저장되었음을, `false`는 중복으로 인해 스킵되었음을 의미한다.

**중복 검사 로직:**

```sql
SELECT COUNT(*) FROM stock_events
WHERE stock_id = ?
  AND event_type = ?
  AND event_time >= ?      -- truncatedTo(MINUTES)
  AND event_time < ?       -- minuteStart + 60s
```

같은 종목에서 같은 이벤트 타입이 동일 분(minute) 내에 이미 존재하면 INSERT를 수행하지 않는다. 이 검사는 애플리케이션 레벨에서 1차로 이루어지고, DB 레벨의 유니크 인덱스가 2차 안전망을 제공한다.

---

## 중복 방지 설계

### V5 마이그레이션의 유니크 인덱스

```sql
CREATE UNIQUE INDEX uq_stock_events_dedup
    ON stock_events (stock_id, event_type, date_trunc('minute', event_time));
```

이 인덱스는 동일 종목(`stock_id`), 동일 이벤트 타입(`event_type`), 동일 분(`date_trunc('minute', event_time)`) 조합에 대해 중복 레코드를 데이터베이스 수준에서 원천 차단한다.

**왜 두 단계의 중복 방지가 필요한가:**

`StockEventWriter.write()`의 SELECT → INSERT 사이에는 race condition이 존재한다. 두 개의 Worker 인스턴스가 동시에 같은 종목의 이벤트를 처리할 경우, 둘 다 SELECT에서 "중복 없음"을 확인하고 INSERT를 시도할 수 있다. DB의 유니크 인덱스는 이 경우 하나를 `unique_violation`으로 거부하여 데이터 정합성을 보장한다.

애플리케이션 레벨 검사는 불필요한 DB 쓰기를 줄이는 최적화이고, DB 레벨 인덱스는 correctness 보장이다.

### 보조 인덱스 구조

```sql
CREATE INDEX idx_stock_events_stock_time      ON stock_events (stock_id, event_time DESC);
CREATE INDEX idx_stock_events_type            ON stock_events (event_type);
CREATE INDEX idx_stock_events_importance      ON stock_events (importance_score DESC);
CREATE INDEX idx_stock_events_stock_type_time ON stock_events (stock_id, event_type, event_time DESC);
```

- `idx_stock_events_stock_time`: 특정 종목의 최신 이벤트 목록 조회 (`/api/stocks/{id}/events`)
- `idx_stock_events_type`: 이벤트 타입 필터링
- `idx_stock_events_importance`: 중요도 높은 이벤트 우선 조회 (`/api/events/recent`)
- `idx_stock_events_stock_type_time`: 중복 검사 쿼리 최적화 (stock_id + event_type + event_time 조합)

---

## 한계와 트레이드오프

### 콜드 스타트 문제

Redis에 EMA 상태가 없는 상태(서버 재시작, 새 종목 추가)에서는 처음 1~2틱은 이벤트를 생성하지 않고 EMA를 초기화하는 데 사용된다. 이후 약 19틱(유효 윈도우 크기) 동안은 기준선이 아직 수렴하지 않아 오탐 또는 미탐이 발생할 수 있다. Redis 재시작 시에는 모든 EMA 상태가 소실된다.

### EMA는 시간을 고려하지 않는다

현재 구현은 틱의 도착 빈도만 반영하고, 실제 시간(장 초반 vs. 장 중)을 구분하지 않는다. 장 시작 직후에는 거래량이 급증하는 경향이 있어 false positive가 많아질 수 있다. 동일 시간대(예: 09:00~09:05)의 평균과 비교하는 같은 시간대 EMA(time-of-day EMA)가 이상적이나, 현 MVP에서는 단순 글로벌 EMA를 사용한다.

### 애플리케이션 레벨 중복 검사의 race condition

앞서 언급한 것처럼 SELECT → INSERT 사이의 race condition은 DB 유니크 인덱스로 최종 처리되지만, 현재 코드는 `unique_violation` 예외를 명시적으로 잡지 않는다. 동시성 상황에서 `DataIntegrityViolationException`이 로그에 기록될 수 있다.

### 변화율 EMA의 수렴 전 동작

`PriceSpikeDetector`에서 변화율 EMA가 `0.001` 미만인 경우 `ratio = 0.0`으로 처리하여 이벤트를 생성하지 않는다. 이는 EMA 수렴 전 또는 가격이 고정된 종목에서 zero-division을 방지하지만, 실제 첫 번째 대규모 가격 움직임을 놓칠 수 있다.

### 단일 Workers 인스턴스 가정

Redis의 EMA 상태 갱신은 read-modify-write 패턴이며, 원자적으로 처리되지 않는다. 복수의 Worker 인스턴스가 동일 종목을 병렬 처리하면 EMA 값이 경쟁 조건에 의해 오염될 수 있다.

---

## 향후 개선 방향

### 1. 같은 시간대 EMA (Time-of-Day EMA)

Redis 키에 시간 버킷을 포함하여 장 시간대별 별도 EMA를 유지한다.

```
detector:volume:ema:{symbol}:{hour}:{5min-bucket}
```

장 초반 급등을 false positive로 처리하지 않으면서, 진짜 이상 거래량을 더 정확하게 감지할 수 있다.

### 2. EMA 갱신의 원자적 처리

Redis의 `GETSET` 또는 Lua 스크립트를 활용하여 read-modify-write를 원자적으로 처리한다. 복수 Worker 인스턴스 환경에서 EMA 상태 오염을 방지한다.

### 3. `unique_violation` 명시적 처리

`StockEventWriter.write()`에서 `DataIntegrityViolationException`을 catch하여 debug 로그로 처리하고, 정상 흐름으로 `false`를 반환한다. 현재는 예외가 상위로 전파되어 Worker 처리 파이프라인을 중단시킬 수 있다.

### 4. EMA 상태 영속화

Redis 재시작 시 EMA 상태가 소실되는 문제를 해결하기 위해, 주기적으로 EMA 상태를 PostgreSQL에 스냅샷으로 저장하고 Worker 시작 시 복원하는 메커니즘을 추가한다.

### 5. importanceScore 동적 보정

현재 score는 ratio 범위에 따른 정적 값이다. 시장 전반의 변동성 지수(예: VKOSPI)를 참조하여 전체 변동성이 높은 날에는 임계값을 높이고, 안정적인 날에는 낮추는 동적 보정을 적용할 수 있다.

### 6. 다중 감지기 조합 신호

같은 시간대에 `PRICE_SPIKE`와 `VOLUME_SURGE`가 동시에 발생한 경우, 이를 더 높은 중요도의 복합 이벤트로 합산하는 상위 집계 레이어를 추가한다. 단일 지표보다 훨씬 신뢰도 높은 신호를 생성할 수 있다.
