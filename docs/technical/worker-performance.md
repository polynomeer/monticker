# Worker 성능 분석 및 병목 해결

## 개요

monticker Worker는 시세 수집, 이벤트 감지, Push 알림 발송을 단일 Spring `@Scheduled` 루프에서 처리한다. 현재 구조에서 드러나는 병목 지점과 종목 수 확장에 따른 성능 한계를 분석하고, 적용된 개선 사항을 기록한다.

---

## 처리 파이프라인

매 1초(`fixedDelay = 1000`)마다 다음 작업이 **단일 스레드에서 순차 실행**된다.

```
collect() {
  for each tick (N 종목):
    RedisTickWriter.write()        ~0.1ms  — RESP 프로토콜, 거의 무시 가능
    PriceTickDbWriter.write()      ~3-8ms  — JDBC INSERT (price_ticks)
    CandleAggregator.onTick()      <1ms    — 순수 in-memory 연산
    EventDetector.detect()
      VolumeSurgeDetector          ~2-5ms  — EMA 계산 (메모리)
      PriceSpikeDetector           ~2-5ms  — EMA 계산 (메모리)
      StockEventWriter.write()     ~5-10ms — SELECT (dedup) + INSERT
        sendEventPush()            100-500ms ← 문제 지점
}
```

### 5종목 기준 최악 케이스 계산

```
5 × (8 + 500) = 2,540ms
```

`fixedDelay=1000` 이므로 이전 사이클이 완료된 뒤 1초를 기다린다. 사이클 자체가 1초를 초과하면 **틱 수집 주기가 늘어나고 실시간성이 저하**된다. 이벤트가 빈번히 발생하는 장 중에는 이 상황이 반복된다.

---

## 병목 1: Push 알림이 Hot Path 안에 있음 (즉각 수정)

### 원인

`StockEventWriter.sendEventPush()`는 이벤트 감지 루프 내에서 Expo Push API에 동기 HTTP 요청을 보낸다.

```kotlin
// 수정 전 — collect() 스레드가 여기서 블로킹
private fun sendEventPush(event: DetectedEvent) {
    val tokens = jdbcTemplate.queryForList(...)  // DB 조회
    pushSender.send(messages)                    // 외부 HTTP — 100-500ms
}
```

Expo API 지연(평균 200ms, 타임아웃 최대 10초) 동안 `collect()` 스레드는 대기한다. 이 상태에서 다음 사이클의 틱이 들어오면 처리가 밀린다.

### 해결

Push 발송을 별도 스레드 풀로 분리하여 hot path에서 즉시 반환한다.

```kotlin
// 수정 후 — collect() 스레드는 submit() 호출 즉시 반환
private val pushExecutor = Executors.newCachedThreadPool()

private fun sendEventPush(event: DetectedEvent) {
    pushExecutor.submit {
        runCatching { sendEventPushAsync(event) }
            .onFailure { log.debug("Event push failed: {}", it.message) }
    }
}
```

`CachedThreadPool`을 선택한 이유: 이벤트는 간헐적이고 버스트성이므로 유휴 스레드를 미리 유지하는 `FixedThreadPool`보다 적합하다. Push 실패는 비치명적이므로 예외를 debug 로그로만 처리한다.

> **참고**: JDK 21 Virtual Thread(`Executors.newVirtualThreadPerTaskExecutor()`)가 더 적합하나, 현재 프로젝트 빌드 타겟은 Java 17이다. JDK 21로 업그레이드 시 교체를 권장한다.

---

## 병목 2: HikariCP 기본 커넥션 풀 (종목 확장 시 문제)

### 현황

Worker는 별도 HikariCP 설정이 없어 기본값(`maximumPoolSize=10`)을 사용한다. 현재 동시에 커넥션을 사용하는 지점은 다음과 같다.

| 작업 | 커넥션 점유 시간 | 종목당 빈도 |
|------|---------------|-----------|
| `price_ticks` INSERT | ~3-8ms | 1회/초 |
| 이벤트 dedup SELECT | ~2-5ms | 감지 시 |
| `stock_events` INSERT | ~2-5ms | 감지 시 |
| `alert_rules` SELECT (AlertEvaluator) | ~3-8ms | 5초마다 |

5종목이면 초당 최대 5개의 동시 INSERT가 발생하므로 기본값으로 충분하다. 그러나 종목 수가 증가하면 커넥션 대기(connection timeout)가 발생한다.

```
100종목 × 8ms = 800ms/cycle → 풀 사용률 80%
200종목 × 8ms = 1,600ms/cycle → 풀 고갈, 3초 타임아웃 후 예외 발생
```

### 조치

```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20       # 동시 쓰기 여유
      minimum-idle: 5
      connection-timeout: 3000    # 3초 — 무한 블로킹 방지
      idle-timeout: 600000
```

`maximum-pool-size=20`은 PostgreSQL 권장 커넥션 수(`2 × CPU core + disk`)와 현재 쓰기 패턴을 감안한 값이다. 종목이 500개를 넘어서면 추가 조정이 필요하다.

---

## 병목 3: @Scheduled 단일 스레드 (종목 확장 시 문제)

Spring의 `@EnableScheduling` 기본 설정은 **단일 스레드** 스케줄러를 사용한다. Worker에는 다음 `@Scheduled` 메서드가 공존한다.

| 메서드 | 주기 |
|--------|------|
| `MarketDataCollector.collect()` | 1초 |
| `MarketDataCollector.flushCandles()` | 60초 |
| `AlertEvaluator.evaluate()` | 5초 |
| `NewsCollector.collect()` | 5분 |
| `DisclosureCollector.collect()` | 10분 |

단일 스레드에서는 이 작업들이 큐에서 순서를 기다린다. `collect()`가 처리 중일 때 `AlertEvaluator.evaluate()`가 트리거되면 1초 이상 지연될 수 있다.

### 조치

```yaml
scheduling:
  pool:
    size: 4
```

각 `@Scheduled` 메서드가 독립된 스레드에서 실행되도록 스레드 풀 크기를 설정한다. `collect()` 자체 내부의 직렬 처리는 변경되지 않으므로, 메서드 간 간섭만 제거된다.

---

## 종목 수에 따른 성능 한계 분석

현재 아키텍처의 성능 한계를 종목 수 기준으로 정리한다.

| 종목 수 | 예상 사이클 시간 | 현재 구조 | 권장 조치 |
|--------|---------------|---------|---------|
| 5개 | ~50ms | 정상 | — |
| 50개 | ~450ms | 정상 | — |
| 100개 | ~900ms | 주의 (1초 근접) | HikariCP 증가 |
| 200개 | ~1,800ms | **주기 초과** | DB 배치 INSERT 도입 |
| 500개 | ~4,500ms | **실시간성 붕괴** | KIS WebSocket 전환 |
| 2,000개+ | — | 현재 구조 불가 | 아키텍처 재설계 필요 |

> 위 수치는 `price_ticks` INSERT ~8ms, 이벤트 감지 ~7ms를 기준으로 한 추정값이다. 실제 PostgreSQL 처리 능력과 네트워크 지연에 따라 달라진다.

---

## KIS API 레이트 리밋 (실시세 연동 시)

`KisPriceProvider`는 1사이클에 활성 종목 전체를 REST API로 폴링한다.

```
KIS 무료 계정 한도: 초당 20 요청
종목 20개 × 1회/초 = 20 req/s → 한계
종목 50개 = 50 req/s → 즉시 초과 (429 Too Many Requests)
```

20종목 이상부터는 REST 폴링이 아닌 **KIS WebSocket 실시간 체결 구독**으로 전환해야 한다. WebSocket은 연결 후 서버 푸시 방식이므로 레이트 리밋에서 자유롭다.

---

## EMA 상태의 단일 인스턴스 의존

`VolumeSurgeDetector`와 `PriceSpikeDetector`는 EMA 상태를 JVM 힙 메모리(ConcurrentHashMap)에 보관한다.

```kotlin
private val emaVolume = ConcurrentHashMap<Long, BigDecimal>()
```

Worker 인스턴스가 여러 개 실행되면 각자 독립된 EMA를 유지하므로 **감지 결과가 인스턴스마다 달라진다**. 현재는 단일 인스턴스 운영을 전제로 하며, 수평 확장이 필요하면 EMA 상태를 Redis에 영속화해야 한다.

```
# 영속화 방향 (미구현)
Redis HSET ema:{stockId} volume {value} price {value}
→ 인스턴스 재시작 후에도 EMA 연속성 유지
→ 복수 인스턴스 간 상태 공유 가능
```

---

## 개선 로드맵

| 단기 (현재 적용) | 중기 | 장기 |
|---------------|------|------|
| Push 비동기 분리 | DB 배치 INSERT | KIS WebSocket 전환 |
| HikariCP 커넥션 증가 | Redis EMA 영속화 | Worker 수평 확장 |
| 스케줄러 스레드 풀 | KIS 종목 페이지 분할 | Kafka 이벤트 스트리밍 |

---

## 관련 파일

- [`MarketDataCollector.kt`](../../backend/worker/src/main/kotlin/com/monticker/worker/marketdata/MarketDataCollector.kt)
- [`StockEventWriter.kt`](../../backend/worker/src/main/kotlin/com/monticker/worker/detector/StockEventWriter.kt)
- [`VolumeSurgeDetector.kt`](../../backend/worker/src/main/kotlin/com/monticker/worker/detector/VolumeSurgeDetector.kt)
- [`application.yml`](../../backend/worker/src/main/resources/application.yml)
- [EMA 이상 탐지 시스템](./ema-event-detection.md)
