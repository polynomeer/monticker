# OpenTelemetry 분산 추적 — 요청 흐름 가시화

## 1. 도입 배경

요청이 느릴 때 "어디서 느린가"를 특정하기 어렵다. 로그에는 요청 시작과 끝은 기록되지만, 그 사이에 DB 쿼리가 10번 있었는지, 외부 API 호출이 있었는지는 보이지 않는다. 서비스가 여러 컴포넌트로 나뉘어 있을수록 이 문제는 심해진다.

OpenTelemetry(OTel)는 요청 하나를 처음부터 끝까지 추적하는 표준이다. 각 처리 단계(스팬)에 시작/종료 시각, 속성, 오류 정보를 기록하고, 이를 스팬 트리로 연결하여 전체 흐름을 시각화한다.

---

## 2. 구성 요소

**Jaeger**: OTel 스팬을 수집하고 시각화하는 오픈소스 도구다. monticker에서는 Jaeger all-in-one Docker 이미지로 실행하며, 수집(OTLP 수신)과 UI 제공을 단일 프로세스에서 처리한다.

**OTLP HTTP 프로토콜**: API 서버와 Worker가 스팬 데이터를 Jaeger로 보낼 때 사용하는 프로토콜이다. gRPC 대신 HTTP를 선택한 이유는 방화벽 설정이 단순하고 Spring Boot의 기본 설정과의 충돌이 없기 때문이다.

```yaml
# application.yml
management:
  otlp:
    tracing:
      endpoint: http://jaeger:4318/v1/traces
  tracing:
    sampling:
      probability: 1.0   # 개발환경: 전체 샘플링
```

**Micrometer 브릿지**: Spring Boot 3.x는 Micrometer Tracing을 통해 OTel SDK와 연동된다. `micrometer-tracing-bridge-otel` 의존성이 이 브릿지를 담당한다. Spring MVC 요청과 JDBC 쿼리는 이 브릿지로 자동 계측된다.

---

## 3. 자동 계측 범위

코드 변경 없이 다음 항목이 자동으로 추적된다.

**Spring MVC 요청**: 모든 HTTP 요청에 스팬이 생성된다. 스팬 이름은 `GET /api/backtest/{stockId}`와 같이 HTTP 메서드와 경로로 구성된다. 요청 성공/실패, 응답 코드, 소요 시간이 기록된다.

**JDBC 쿼리**: `spring-boot-starter-jdbc` 또는 JPA를 통한 모든 SQL 쿼리에 스팬이 생성된다. 어떤 SQL이 얼마나 걸렸는지 Jaeger UI에서 바로 확인할 수 있다.

이 두 가지 자동 계측만으로도 "HTTP 요청 처리 중 DB 쿼리가 얼마나 걸렸는가"를 즉시 파악할 수 있다.

---

## 4. 커스텀 스팬 설계

자동 계측이 포착하지 못하는 비즈니스 로직 단위에는 `Tracing.span()`으로 수동 스팬을 추가한다.

```kotlin
object Tracing {
    private val tracer by lazy {
        GlobalOpenTelemetry.getTracer("monticker", "1.0.0")
    }

    fun <T> span(name: String, attributes: Map<String, Any> = emptyMap(), block: (Span) -> T): T {
        val span = tracer.spanBuilder(name)
            .setParent(Context.current())   // 현재 활성 스팬의 자식으로 생성
            .startSpan()
        return try {
            attributes.forEach { (k, v) -> /* 타입별 setAttribute */ }
            span.makeCurrent().use { block(span) }
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "error")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }
}
```

`setParent(Context.current())`가 핵심이다. 현재 활성화된 스팬의 자식으로 새 스팬을 만들어 계층 구조를 유지한다. Spring MVC 자동 스팬이 부모가 되고, 커스텀 스팬이 자식이 된다.

오류 발생 시 `recordException(e)`으로 스택 트레이스를 스팬에 첨부하고, `setStatus(ERROR)`로 Jaeger UI에서 오류 스팬이 빨간색으로 표시되게 한다.

### 백테스팅 스팬

```kotlin
Tracing.span("backtest.run", mapOf(
    "stockId"     to request.stockId,
    "strategy"    to request.strategy.name,
    "candleCount" to candles.size,
    "tradeCount"  to result.trades.size,
    "totalReturn" to result.metrics.totalReturn,
)) { result }
```

이 속성들로 Jaeger에서 "totalReturn이 10% 이상인 backtest.run 스팬"을 검색하거나, 특정 전략의 평균 실행 시간을 비교할 수 있다.

### 스크리너 스팬

```kotlin
Tracing.span("screener.getItems", mapOf(
    "tab"         to tab,
    "market"      to market,
    "sort"        to sort,
    "resultCount" to items.size,
)) { items }
```

탭별, 정렬 조건별 응답 시간 차이를 추적할 수 있다. 예를 들어 `거래대금` 정렬이 다른 정렬보다 느리다면 해당 쿼리 최적화 필요성을 발견할 수 있다.

---

## 5. Jaeger UI 활용

**서비스 선택**: 왼쪽 상단 드롭다운에서 `monticker-api` 또는 `monticker-worker`를 선택한다.

**트레이스 검색**: 서비스, 오퍼레이션 이름, 태그, 시간 범위, 최소 소요 시간으로 필터링할 수 있다.
- `operation: backtest.run` — 백테스트 실행 트레이스만 조회
- `tags: strategy=RSI` — RSI 전략 실행만 조회
- `min duration: 2s` — 2초 이상 걸린 요청만 조회

**스팬 타임라인**: 트레이스를 클릭하면 스팬 계층 구조가 타임라인으로 표시된다. 가로 막대의 길이가 소요 시간이고, 들여쓰기가 부모-자식 관계를 나타낸다. JDBC 쿼리 스팬이 전체 요청 시간의 90%를 차지한다면 DB 쿼리 최적화가 필요하다는 신호다.

---

## 6. 샘플링 전략

모든 요청을 추적하면 오버헤드가 크다. 운영 환경에서는 샘플링으로 수집 비율을 낮춘다.

| 환경 | 샘플링 비율 | 설정 |
|------|-------------|------|
| 개발 | 100% | `probability: 1.0` |
| 스테이징 | 10% | `probability: 0.1` |
| 운영 | 1~5% | `probability: 0.01` ~ `0.05` |

운영에서 1%로 설정해도 초당 100 요청이면 초당 1개의 트레이스가 수집되어 충분한 샘플을 얻는다. 오류가 발생한 요청은 샘플링 비율과 무관하게 항상 기록하도록 Head-based 대신 Tail-based 샘플링을 적용하는 것이 이상적이지만, 현재는 확률 기반 Head-based 샘플링만 사용한다.

---

## 7. 컨텍스트 전파

TraceId는 HTTP 요청에서 시작하여 DB 쿼리까지 이어진다.

```
HTTP 요청 (TraceId: abc123)
  └─ Spring MVC 스팬
       └─ BacktestService.run 스팬 (Tracing.span)
            └─ JDBC 쿼리 스팬 (자동 계측)
                  SELECT * FROM candles_1d WHERE ...
```

`setParent(Context.current())`가 각 단계에서 현재 컨텍스트를 상속하므로 TraceId가 유지된다. Spring MVC 자동 계측이 `Context`에 TraceId를 심으면, 이후 생성되는 모든 자식 스팬이 같은 TraceId를 갖는다.

서비스 간 전파(HTTP)는 `traceparent` 헤더로 이루어진다. Worker가 외부 API를 호출할 때 이 헤더를 포함하면 외부 서비스도 같은 트레이스에 참여한다. 단, 외부 서비스가 OTel을 지원해야 한다.

---

## 8. 한계

**Worker-API 간 분산 추적 미연결**: Worker(데이터 수집, 이벤트 감지)와 API 서버는 별개의 JVM 프로세스다. Worker가 내부 이벤트를 처리하면서 API에 영향을 줄 때(예: Redis 캐시 갱신), 두 서비스의 스팬이 같은 트레이스로 연결되지 않는다. 각각 독립된 트레이스로 기록된다.

**비동기 Push 스팬 단절**: Expo Push 알림 발송은 비동기로 처리된다. 이벤트 감지 → 알림 발송 흐름에서 컨텍스트가 코루틴이나 스레드 경계를 넘어가면 TraceId가 끊어진다. Kotlin 코루틴에서 OTel 컨텍스트를 전파하려면 `kotlinx-coroutines-opentelemetry` 라이브러리와 추가 설정이 필요하다.
