# Circuit Breaker — 외부 API 장애 격리

## 개요

Worker는 세 가지 외부 HTTP API에 의존한다. 이 중 하나라도 느려지거나 다운되면
`@Scheduled(fixedDelay=1000)` 루프 전체가 블로킹되어 시세 수집과 이벤트 감지가
멈춘다. Circuit Breaker는 장애가 발생한 외부 의존성을 격리하여 나머지 파이프라인이
계속 동작하도록 보장한다.

```
외부 API 장애
      │
      ▼
Circuit Breaker OPEN  ──→  CallNotPermittedException
      │                            │
      │                            ▼
      │                     폴백(Mock) 실행
      │                            │
      ▼ (대기 후 HALF_OPEN)        ▼
  복구 탐색                   파이프라인 계속 동작
```

---

## 보호 대상

| 서비스 | 클래스 | 실패 영향 |
|--------|--------|----------|
| **KIS Developers API** | `KisClient.fetchPrice()` | 실 시세 수집 중단 → Mock 폴백 |
| **Expo Push API** | `ExpoPushSender.send()` | 알림 발송 실패 → 무음 skip |
| **Naver 뉴스 API** | `NaverNewsClient.search()` | 뉴스 수집 중단 → Mock 뉴스 폴백 |

---

## 상태 머신

Resilience4j Circuit Breaker는 세 상태를 갖는다.

```
          실패율 ≥ 임계값
CLOSED ─────────────────→ OPEN
  ↑                         │
  │  성공율 ≥ 임계값          │ 대기 시간 경과
  │                         ▼
  └────────────────── HALF_OPEN
          N회 탐색 요청
```

| 상태 | 동작 |
|------|------|
| **CLOSED** | 모든 요청 통과. 슬라이딩 윈도우로 실패율 측정. |
| **OPEN** | 모든 요청 즉시 차단 (`CallNotPermittedException`). 대기 시간 후 HALF_OPEN 전환. |
| **HALF_OPEN** | N개 요청만 통과시켜 복구 여부 탐색. 성공 시 CLOSED, 실패 시 다시 OPEN. |

---

## 브레이커별 파라미터

### `kisApi` — KIS 실 시세

```yaml
failureRateThreshold: 50       # 10회 중 5회 실패 시 OPEN
slidingWindowSize: 10
waitDurationInOpenState: 30s
permittedNumberOfCallsInHalfOpenState: 3
```

**폴백 동작**: `KisPriceProvider.fetchTicks()`가 빈 목록을 반환하면
`MarketDataCollector`가 `MockPriceGenerator`로 자동 전환한다.

```kotlin
// MarketDataCollector.collect()
val kisTicks = kisPriceProvider.fetchTicks()
val ticks = if (kisTicks.isNotEmpty()) kisTicks else generator.generate()
```

### `expoPush` — Expo Push 알림

```yaml
failureRateThreshold: 60       # 5회 중 3회 실패 시 OPEN
slidingWindowSize: 5
waitDurationInOpenState: 60s
permittedNumberOfCallsInHalfOpenState: 2
```

**폴백 동작**: `CallNotPermittedException` 발생 시 `warn` 로그만 남기고 빈 목록
반환. Push 알림은 비치명적이므로 더 관대한 임계값을 적용했다.

```kotlin
return try {
    cb.executeCallable { sendInternal(messages) }
} catch (e: CallNotPermittedException) {
    log.warn("[CircuitBreaker:expoPush] OPEN — Push 발송 건너뜀 ({}건)", messages.size)
    emptyList()
}
```

### `naverNews` — Naver 뉴스

```yaml
failureRateThreshold: 50       # 4회 중 2회 실패 시 OPEN
slidingWindowSize: 4
waitDurationInOpenState: 5m    # 뉴스 수집 주기(5분)와 동일
permittedNumberOfCallsInHalfOpenState: 1
```

**폴백 동작**: `NewsCollector.collectForStock()`이 브레이커 OPEN 상태를 감지하면
`MockNewsGenerator.generate()`를 호출한다.

---

## 구현 방식: 프로그래매틱 vs AOP

Resilience4j는 `@CircuitBreaker` 어노테이션(AOP)과 프로그래매틱 API 두 방식을
지원한다. 이 프로젝트는 **프로그래매틱 방식**을 선택했다.

### AOP 방식 (미사용)
```kotlin
@CircuitBreaker(name = "kisApi", fallbackMethod = "fallbackFetch")
fun fetchPrice(symbol: String): KisPrice? { ... }
```
- 장점: 코드 간결
- 단점: Spring AOP 프록시 필요 (`@EnableAspectJAutoProxy`), Worker에 `spring-boot-starter-web`이 없어 추가 설정 부담

### 프로그래매틱 방식 (채택)
```kotlin
val cb = cbRegistry.circuitBreaker("kisApi")
return cb.executeCallable { fetchPriceInternal(symbol) }
```
- 장점: AOP 없이 동작, 상태와 폴백 흐름이 코드에 명시적
- 단점: 약간의 보일러플레이트

---

## 상태 전이 로깅

모든 브레이커의 상태 전이는 `WARN` 레벨로 기록된다.

```
[CircuitBreaker:kisApi] CLOSED → OPEN
[CircuitBreaker:kisApi] OPEN → HALF_OPEN
[CircuitBreaker:kisApi] HALF_OPEN → CLOSED

[CircuitBreaker:kisApi] 요청 차단됨 (OPEN 상태)  ← DEBUG
```

### 운영 시 확인 방법

```bash
# Worker 로그에서 Circuit Breaker 이벤트 필터링
tail -f logs/worker.log | grep "CircuitBreaker"

# Spring Actuator health (future: Resilience4j health indicator 추가 시)
curl http://localhost:8080/actuator/health
```

---

## 장애 시나리오 시뮬레이션

```bash
# KIS API 차단 테스트 — Redis에 잘못된 KIS 설정 주입 후
# application.yml의 kis.app-key를 invalid로 변경하고 Worker 재시작
# → 10회 실패 → OPEN → Mock 폴백 확인
```

---

## 파라미터 선택 근거

**왜 슬라이딩 윈도우인가**

COUNT_BASED 슬라이딩 윈도우를 사용한다. TIME_BASED는 분당 요청 수가 적을 때
통계가 희박해지는 문제가 있다. Worker의 KIS 폴링은 ~1초 주기이므로 10회 = 약 10초
분량의 최근 이력을 기준으로 삼는 것이 적절하다.

**왜 브레이커마다 임계값이 다른가**

- KIS API: 실 시세는 정확해야 하므로 보수적으로 설정(50%).
- Expo Push: 알림 실패는 서비스 중단으로 이어지지 않으므로 관대하게(60%).
- Naver 뉴스: 수집 주기가 5분이므로 대기 시간도 5분으로 맞춰 복구 타이밍을 일치시켰다.

---

## 한계

1. **상태 비영속성**: 브레이커 상태가 JVM 메모리에만 존재한다. Worker 재시작 시
   항상 CLOSED로 초기화된다.
2. **단일 인스턴스**: 여러 Worker 인스턴스를 운영하면 각자 독립된 상태를 가진다.
   분산 Circuit Breaker가 필요하면 Redis 기반 공유 상태로 교체해야 한다.
3. **Actuator 미연동**: 현재 브레이커 상태를 `/actuator/health`에서 확인할 수 없다.
   `resilience4j-spring-boot3` 의존성을 추가하면 자동 연동된다.

---

## 향후 개선

```kotlin
// 1. Actuator health 연동
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

// 2. Retry + CircuitBreaker 조합 (transient failure 처리)
val retry = Retry.of("kisApi", RetryConfig.custom()
    .maxAttempts(3)
    .waitDuration(Duration.ofMillis(500))
    .build())
val decorated = Decorators.ofCallable { fetchPriceInternal(symbol) }
    .withRetry(retry)
    .withCircuitBreaker(cb)
    .decorate()
```

---

## 관련 파일

- [`CircuitBreakerRegistry.kt`](../../backend/worker/src/main/kotlin/com/monticker/worker/resilience/CircuitBreakerRegistry.kt)
- [`KisClient.kt`](../../backend/worker/src/main/kotlin/com/monticker/worker/kis/KisClient.kt)
- [`ExpoPushSender.kt`](../../backend/worker/src/main/kotlin/com/monticker/worker/push/ExpoPushSender.kt)
- [`NaverNewsClient.kt`](../../backend/worker/src/main/kotlin/com/monticker/worker/news/NaverNewsClient.kt)
- [Worker 성능 분석](./worker-performance.md)
