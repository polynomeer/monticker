# 신뢰성 패턴 모음

monticker 백엔드에 적용된 신뢰성·회복력 패턴을 기술한다.  
각 패턴은 단독 장애를 격리하거나, 부분 실패로부터 복구하거나, 시스템 과부하를 방지하는 역할을 한다.

---

## 목차

1. [Kafka Dead Letter Topic (DLT)](#1-kafka-dead-letter-topic-dlt)
2. [Idempotency Key — 중복 결제 방지](#2-idempotency-key--중복-결제-방지)
3. [Graceful Shutdown](#3-graceful-shutdown)
4. [Bulkhead — 백테스트 격리](#4-bulkhead--백테스트-격리)
5. [Distributed Lock — 분산 중복 실행 방지](#5-distributed-lock--분산-중복-실행-방지)
6. [Request ID / MDC Tracing](#6-request-id--mdc-tracing)
7. [Rate Limiting — 다계층 보호](#7-rate-limiting--다계층-보호)
8. [패턴 간 상호작용 요약](#8-패턴-간-상호작용-요약)

---

## 1. Kafka Dead Letter Topic (DLT)

### 도입 배경

`market.ticks` 컨슈머가 역직렬화 실패·DB 오류 등으로 예외를 던지면, 기본 설정에서는 해당 레코드에서 Kafka 파티션이 영구 블로킹된다.

### 설계

```
market.ticks
  │
  ▼
TickKafkaConsumer (@RetryableTopic)
  │  실패 → 재시도
  │  attempts = 3, backoff = 2초 × 2배수
  │
  ├─ market.ticks-retry-0   (1차 재시도)
  ├─ market.ticks-retry-1   (2차 재시도)
  └─ market.ticks-dlt       (최종 실패 → @DltHandler 로깅)
```

```kotlin
@RetryableTopic(
    attempts = "3",
    backoff = Backoff(delay = 2_000, multiplier = 2.0),
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
    dltTopicSuffix = "-dlt",
    autoCreateTopics = "false",
)
@KafkaListener(topics = ["market.ticks"], groupId = "monticker-worker")
fun onTick(record: ConsumerRecord<String, String>) { ... }

@DltHandler
fun onTickDlt(record: ConsumerRecord<String, String>) {
    log.error("[DLT] market.ticks 최종 실패 — 수동 검토 필요. topic={} partition={} offset={}",
        record.topic(), record.partition(), record.offset())
}
```

### 트레이드오프

| 항목 | 결정 |
|------|------|
| `autoCreateTopics = "false"` | 토픽은 인프라 코드(Terraform/K8s)로 사전 생성 |
| DLT 알림 | 현재 로그만. 모니터링 알림 연결 시 DLT Consumer Group 별도 구성 가능 |
| 재시도 간격 | 2초 → 4초: 일시적 DB 지연에 충분, 파티션 블로킹 시간은 최대 6초 |

---

## 2. Idempotency Key — 중복 결제 방지

### 도입 배경

모바일 네트워크 지연으로 클라이언트가 동일 주문을 두 번 전송하는 시나리오를 방지한다.  
`POST /api/paper/buy`, `/api/paper/sell`, `/api/matching/orders` 세 엔드포인트가 대상이다.

### 설계

```
Client  ──── POST /api/paper/buy ────► IdempotencyFilter
              X-Idempotency-Key: abc123      │
                                             ├─ Redis 조회: idempotency:{userId}:abc123
                                             │   HIT  → 캐시 응답 반환 (200/201)
                                             │   MISS → 체인 통과 → 처리
                                             │          응답 200~299이면 Redis 저장 (TTL 24h)
                                             └─ ContentCachingResponseWrapper로 응답 캡처
```

```kotlin
// Redis key 구조
"idempotency:{userId}:{X-Idempotency-Key}"
// 값: JSON { "status": 200, "body": "..." }
// TTL: 24시간
```

### 보안 고려사항

- 키를 `userId`와 함께 네임스페이스에 격리해 타 사용자의 키와 충돌을 방지한다.
- `X-Idempotency-Key` 없이도 처리는 되지만 중복 보호가 없다. 클라이언트 SDK가 자동 생성하도록 권장.

### 적용 경로 제한

멱등성 보호가 필요하지 않은 GET/DELETE는 `shouldNotFilter()`에서 조기 리턴한다.  
POST 외 메서드와 비대상 경로도 제외한다.

---

## 3. Graceful Shutdown

### 도입 배경

K8s가 Pod를 교체할 때 `SIGTERM`을 보내고 30초 뒤 `SIGKILL`한다.  
기본 설정에서는 진행 중인 요청이 즉시 끊긴다.

### 설정

```yaml
# application.yml (api, worker 공통)
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

`graceful` 모드에서 Spring Boot는:
1. 새 요청 수락 중단
2. 진행 중인 요청이 완료될 때까지 대기 (최대 30초)
3. Executor 등 Bean의 `@PreDestroy` 순서대로 종료

### Executor 연동

```kotlin
// AsyncConfig.kt
ThreadPoolTaskExecutor().apply {
    setWaitForTasksToCompleteOnShutdown(true)
    setAwaitTerminationSeconds(60)   // 백테스트는 최대 60초 대기
}
```

Kafka 컨슈머는 `spring.kafka.listener.immediate-stop=false`(기본값)로 폴링 루프가 완료 후 종료된다.

---

## 4. Bulkhead — 백테스트 격리

### 도입 배경

장기 실행되는 백테스트 요청이 API 서버의 공용 스레드 풀을 고갈시키는 시나리오를 격리한다.

### 설계

```
HTTP 요청 스레드
  │
  └─► BacktestController
        ├─ CompletableFuture.supplyAsync(task, backtestExecutor)
        └─ .get() 블로킹 대기

backtestExecutor (전용 풀)
  corePoolSize = 2
  maxPoolSize  = 4
  queueCapacity = 20
  → 큐 포화 시 RejectedExecutionException → HTTP 429
```

```kotlin
// BacktestController.kt
fun run(@RequestBody req: BacktestRequestDto): ResponseEntity<*> =
    try {
        val result = CompletableFuture.supplyAsync({ service.run(req) }, executor).get()
        ResponseEntity.ok(result)
    } catch (e: RejectedExecutionException) {
        ResponseEntity.status(429).body(mapOf("error" to "백테스트 대기 중. 잠시 후 재시도해주세요."))
    }
```

### 스레드 풀 크기 선택 근거

| 파라미터 | 값 | 이유 |
|----------|-----|------|
| corePoolSize | 2 | 동시 백테스트 2개로 CPU 집약 연산 제한 |
| maxPoolSize | 4 | 순간 burst 허용 |
| queueCapacity | 20 | 20개 이상 누적 → 거부 (과부하 신호) |

---

## 5. Distributed Lock — 분산 중복 실행 방지

### 도입 배경

Worker가 수평 스케일링될 때 동일 스케줄 작업이 여러 인스턴스에서 동시 실행되어 중복 수집·중복 삽입이 발생하는 문제를 방지한다.

### 구현

```kotlin
// @DistributedLock AOP 어노테이션
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DistributedLock(val name: String, val ttlSeconds: Long = 300)

// DistributedLockAspect.kt — Redis SETNX 기반
@Around("@annotation(com.monticker.worker.common.DistributedLock)")
fun around(pjp: ProceedingJoinPoint): Any? {
    val key = "distributed-lock:${lock.name}"
    val acquired = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(lock.ttlSeconds))
    if (acquired != true) { log.debug("건너뜀: {}", lock.name); return null }
    return try { pjp.proceed() } finally { redis.delete(key) }
}
```

### 사용 예시

```kotlin
@Scheduled(fixedDelay = 1_800_000)
@DistributedLock(name = "news-collector", ttlSeconds = 1_500)
fun collect() { ... }
```

### 주의사항

- TTL은 실제 실행 시간보다 짧게 설정하면 안 된다. 1,500초(25분)는 30분 주기보다 충분히 작다.
- Redis 장애 시 `setIfAbsent`가 `null`을 반환하면 잠금을 획득하지 못한 것으로 처리한다 (fail-safe: 중복 허용, 실행 안 함).

---

## 6. Request ID / MDC Tracing

### 도입 배경

다중 요청이 동시에 처리될 때 로그를 요청 단위로 추적하기 어렵다.  
NGINX → Spring → Kafka 추적 체인을 구축한다.

### 흐름

```
Client ──► NGINX (Ingress)
             set $req_id $request_id;  ← NGINX가 없으면 UUID 생성
             proxy_set_header X-Request-Id $req_id;
             │
             ▼
           RequestIdFilter (Spring, @Order=1)
             MDC.put("requestId", reqId)
             response.setHeader("X-Request-Id", reqId)
             │
             ▼
           비즈니스 로직 처리 (모든 로그에 requestId 포함)
             │
             ▼
           MDC.remove("requestId")  ← finally 블록
```

### 로그 패턴

```xml
<!-- logback-spring.xml -->
<pattern>%d{HH:mm:ss} [%X{requestId}] %-5level %logger{36} - %msg%n</pattern>
```

출력 예:
```
14:23:01 [a3f9b2c1-...] INFO  MatchingService - 주문 제출 userId=42 stockId=7
14:23:01 [a3f9b2c1-...] DEBUG Saga           - [Saga:e7d...] 시작
```

---

## 7. Rate Limiting — 다계층 보호

### 계층 구조

```
Internet
  │
  ▼
NGINX Ingress ── 60rps / IP, burst × 5, max-connections 20
  │
  ▼
RateLimitFilter (Spring) ── 엔드포인트별 Redis 슬라이딩 윈도우
  │
  ├─ /api/auth/login   → 10req / 1분 / IP
  ├─ /api/auth/signup  →  5req / 10분 / IP
  ├─ /api/auth/refresh → 20req / 1분 / IP
  └─ /api/**           → 300req / 1분 / IP
```

### NGINX 설정 (Ingress Annotation)

```yaml
nginx.ingress.kubernetes.io/limit-rps: "60"
nginx.ingress.kubernetes.io/limit-connections: "20"
nginx.ingress.kubernetes.io/limit-burst-multiplier: "5"
```

### Spring 레이어 — X-Forwarded-For 처리

NGINX 프록시 환경에서 `req.remoteAddr`는 NGINX Pod IP를 반환한다.  
실제 클라이언트 IP를 추출하기 위해 `X-Forwarded-For` 헤더 첫 번째 값을 사용한다.

```kotlin
val ip = req.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
         ?: req.remoteAddr
```

### 사용자 기반 Rate Limit (`@RateLimited`)

IP 기반 외에 userId 기반 제한도 별도 적용한다.  
`RateLimitAspect`가 `@RateLimited` 어노테이션 메서드에 적용된다.

---

## 8. 패턴 간 상호작용 요약

```
요청 수신
  │
  ├─ [NGINX] RPS 초과 → 429 즉시 반환
  ├─ [RequestIdFilter] MDC requestId 설정
  ├─ [RateLimitFilter] IP 슬라이딩 윈도우 체크
  ├─ [IdempotencyFilter] 캐시 HIT → 즉시 반환
  │
  └─ 비즈니스 로직
       ├─ [DistributedLock] 스케줄 작업 단일 실행 보장
       ├─ [Bulkhead] 백테스트 전용 풀 격리
       └─ [Saga] 주문 흐름 보상 트랜잭션 보장

응답 후
  └─ [MDC] requestId 제거

Kafka 소비
  └─ [@RetryableTopic] 실패 시 재시도 → DLT 최종 실패 격리

종료
  └─ [Graceful Shutdown] 진행 중 요청 완료 후 종료
```

---

## 관련 문서

- [circuit-breaker.md](./circuit-breaker.md) — 외부 HTTP 호출 Circuit Breaker
- [kafka-tick-pipeline.md](./kafka-tick-pipeline.md) — Kafka 소비 파이프라인 전체
- [order-saga.md](./order-saga.md) — 주문 Saga 오케스트레이터
- [eda-event-driven-architecture.md](./eda-event-driven-architecture.md) — EDA 패턴
