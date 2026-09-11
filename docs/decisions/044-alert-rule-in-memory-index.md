# ADR-044: 알림 룰 인메모리 인덱스 — 틱당 DB 조회 제거와 평가·발송 분리

## Status
Accepted

## Context

[`AlertEvaluator`](../../backend/worker/src/main/kotlin/com/monticker/worker/alert/AlertEvaluator.kt)의
헤더 주석은 이 컴포넌트가 이미 한 번 최적화됐다고 적고 있다:

> [Before] `@Scheduled(fixedDelay=5000)`: 전체 alert_rules 폴링 → 각 종목 현재가 DB 재조회
> [After] `@EventListener(TickProcessedEvent)`: 틱마다 해당 stockId 규칙만 평가

폴링에서 이벤트 기반으로 바뀐 건 개선이 맞다. 하지만 **DB 접근은 줄어든 게 아니라
"전체 스캔 1회/5초"에서 "종목별 조회 1회/틱"으로 형태만 바뀌었다.**

### 결함 1 — 틱마다 룰을 DB에서 읽는다

```kotlin
fun processAlert(stockId: Long, price: BigDecimal) {
    val rules = fetchRulesForStock(stockId)     // SELECT ... WHERE stock_id = ? AND is_active = true
    for (rule in rules) evaluateRule(rule, price)
}
```

처리된 틱 1건당 Postgres 쿼리 1건이다.

| 규모 | 틱 레이트 | 룰 조회 쿼리 |
|------|----------|-------------|
| T1 | 3,000/s | 3,000 SELECT/s |
| **T2** | 30,000/s | **30,000 SELECT/s** |

인덱스(`idx_alert_rules_stock_id`)가 있어도 커넥션 점유·플래너·네트워크 왕복 비용이
그대로 나간다. 게다가 이 경로는 `@Async("alertDispatchExecutor")`라, 스레드풀이 포화되면
알림이 조용히 큐에 밀린다.

### 결함 2 — `VOLUME_SURGE`는 룰마다 집계 쿼리를 한 번 더 돈다

```kotlin
"VOLUME_SURGE" -> {
    val row = jdbc.queryForMap("""
        SELECT (SELECT volume FROM candles_1d WHERE stock_id = ? AND candle_time >= DATE_TRUNC('day', ...)) AS today_vol,
               (SELECT AVG(volume) FROM candles_1d WHERE stock_id = ? AND candle_time >= NOW() - INTERVAL '$period days' ...) AS avg_vol
    """, rule.stockId, rule.stockId)
    ...
}
```

**틱 × 해당 종목의 VOLUME_SURGE 룰 수**만큼 20일치 캔들 집계가 돈다.
결함 1보다 한 자릿수 더 비싸다.

같은 코드의 주석이 이 경로의 또 다른 문제를 기록하고 있다:

> 이전 쿼리는 비집계 컬럼과 집계를 GROUP BY 없이 섞은 무효 SQL이라 매번 예외를 던졌고
> `processAlert()`의 바깥 try/catch가 조용히 삼켜 **이 규칙이 한 번도 발동한 적이 없었다.**

즉 이 경로는 **비용이 큰 데다, 실패해도 아무도 모르는 구조**였다.
쿼리는 고쳐졌지만 "예외를 삼키는 구조"는 그대로다.

### 결함 3 — 평가와 발송이 같은 스레드에 묶여 있다

`dispatchAlert`는 Redis SETNX → `INSERT alert_histories` → `SELECT device_tokens` →
Expo 푸시 HTTP → `UPDATE alert_histories` → ES 색인을 순차로 한다.
쿨다운(10분)이 있어 호출 빈도는 낮지만, **Expo나 SMTP가 느려지면 그 지연이
`alertDispatchExecutor` 스레드를 잡고 결국 틱 평가까지 밀린다.**
Circuit Breaker(`expoPush`)가 있어 완전 장애는 막지만, 느린 응답은 막지 못한다.

## Decision

### 1. 룰을 워커 메모리에 상주시킨다

`worker-alert`(및 `role=all`) 기동 시 활성 룰 전체를 로드해 종목별 인덱스를 만든다.

```kotlin
class AlertRuleIndex {
    // stockId → 그 종목의 룰들
    private val byStock = ConcurrentHashMap<Long, StockRules>()

    class StockRules(
        // 임계가 오름차순 — price보다 작은 것들이 발동 대상
        val priceAbove: Array<ThresholdRule>,
        // 임계가 내림차순 — price보다 큰 것들이 발동 대상
        val priceBelow: Array<ThresholdRule>,
        val volumeSurge: Array<VolumeRule>,
    )
}
```

틱 평가는 **정렬된 배열 이진 탐색**으로 발동 대상 구간만 잘라낸다.
종목당 룰이 수천 개여도 스캔하지 않는다.

> **의미론을 바꾸지 않는다.** 현재 규칙은 "가격이 임계를 넘어선 상태면 발동
> (쿨다운 10분으로 반복 억제)"이라는 **레벨 기반**이다. 이진 탐색은 같은 집합을 더 빨리
> 찾을 뿐, "돌파 시점에만 발동"하는 **교차 기반**으로 바꾸지 않는다.
> 의미론 변경은 사용자에게 보이는 동작 변화이므로 이 ADR의 범위가 아니다.

T2 추정: 활성 룰 500만 건, 종목당 평균 400건 → 룰 객체 수백 MB 수준.
워커 힙에 상주 가능하다. 넘어서면 종목 샤딩(`WORKER_SHARD_INDEX`)으로 나눈다.

### 2. `VOLUME_SURGE`의 DB 의존을 없앤다

두 값을 모두 메모리/Redis에서 읽는다.

| 값 | 지금 | 바꾼 뒤 |
|----|------|--------|
| `avg_vol` (N일 평균) | 틱마다 `AVG(volume)` 집계 | **장 시작 전 배치 1회** → `Map<stockId, avgVol>` (하루 동안 상수) |
| `today_vol` (당일 누적) | 틱마다 `candles_1d` 조회 | 틱 파이프라인이 누적 → Redis `vol:today:{stockId}` |

`avg_vol`은 확정된 과거 거래일 평균이라 장중에 변하지 않는다 — **틱마다 다시 계산할
이유가 처음부터 없었다.** `today_vol`은 `CandleAggregator`가 이미 당일 볼륨을 누적하고
있으므로 그 값을 Redis에 반영한다([ADR-042](042-outbox-based-es-indexing.md)와 무관,
[scale-out-plan §6.2.3](../scale-out-plan.md)의 캔들 파이프라인 재설계와 함께 간다).

### 3. 변경 전파는 Redis pub/sub — CDC를 쓰지 않는다

`AlertService`가 룰의 **유일한 쓰기 경로**다(`createRule` / `deactivateRule`).
따라서 발행 지점도 한 곳이다.

```
AlertService.createRule/deactivateRule  (커밋 후)
   └─► redis.publish("alert:rules:changed", "{stockId}")
          └─► 모든 worker-alert 인스턴스가 구독 → 해당 stockId만 재로드
```

- 전파 대상이 **모든** 워커 인스턴스이므로 pub/sub(팬아웃)이 맞다.
  Kafka 컨슈머 그룹은 파티션을 나눠 갖기 때문에 부적합하다
  ([ADR-038](038-broadcast-consumer-partition-assignment.md)에서 확인한 것과 같은 함정).
- pub/sub은 at-most-once다. 메시지를 놓치면 인덱스가 낡는다 →
  **5분 주기로 `updated_at > lastSync`인 룰만 폴링해 보정**한다(저비용 안전망).
- 발행은 커밋 후(`@TransactionalEventListener(AFTER_COMMIT)`)에 한다 —
  롤백된 룰 변경이 전파되면 안 된다.

### 4. 평가와 발송을 분리한다

```
[worker-alert] 틱 → 인덱스 조회 → 발동 판정 → Kafka notify.commands 발행 (끝)
[worker-notify] notify.commands 소비 → 쿨다운 → alert_histories INSERT → 푸시/이메일 → 상태 UPDATE
```

틱 평가 경로에서 외부 I/O(Expo, SMTP, ES)를 완전히 제거한다.
`notify.commands` 토픽은 [ADR-040](040-kafka-topic-declaration.md)의 표에 추가한다
(key = `ruleId`, 재시도/DLT 포함).

### 5. 예외를 삼키지 않는다

`processAlert`의 바깥 `try/catch`가 모든 실패를 로그로 흡수하는 구조를 바꾼다.
룰 타입별 평가 실패를 `alert_rule_eval_failed_total{ruleType}` 카운터로 노출하고,
0보다 크면 알람한다. 결함 2의 "한 번도 발동한 적 없었다"는 사고가 이 메트릭만
있었어도 즉시 드러났다.

## Reasons

- **룰 데이터는 캐시하기에 이상적이다.** 읽기는 초당 수만 번, 쓰기는 초당 수 건.
  변경 주체가 하나(`AlertService`)이고, 데이터 총량이 메모리에 들어간다.
  이 조합이면 DB를 매번 때릴 이유가 없다.
- **CDC를 쓰지 않는 이유**: 쓰기 경로가 이미 한 곳으로 모여 있어 발행 지점을 놓칠 위험이
  없다. CDC의 고정비(`wal_level=logical`, Kafka Connect 운영, replication slot 미소비 시
  WAL 디스크 풀)를 정당화하려면 "쓰기 경로가 많아 전부 고칠 수 없다"는 조건이 필요한데
  여기선 해당하지 않는다. 같은 판단 근거를 [ADR-042](042-outbox-based-es-indexing.md)가
  ES 인덱싱에 대해 이미 적용했다.
- **`avg_vol`을 배치로 옮기는 게 가장 큰 절감이다.** 결함 2는 결함 1보다 비싸다.
  그리고 "하루 동안 변하지 않는 값을 초당 3만 번 계산하고 있었다"는 건
  캐시 문제가 아니라 계산 위치가 잘못된 문제다.
- **평가/발송 분리를 지금 하는 이유**: 인덱스만 넣으면 평가가 빨라지는데,
  발송이 같은 스레드에 남아 있으면 병목이 그대로 옮겨간다. 두 작업을 함께 해야
  틱 파이프라인이 외부 서비스 지연으로부터 실제로 격리된다.

## Consequences

- **워커 메모리 사용량이 룰 수에 비례해 증가한다.** 지금 규모에선 무시할 만하지만,
  `alert_rule_index_size` 메트릭을 노출해 증가 추이를 본다. 힙 한계에 닿으면
  종목 샤딩으로 넘어간다.
- **룰 변경 반영이 동기에서 준실시간이 된다.** 알림 규칙을 만들고 나서 다음 틱까지
  수십 ms, pub/sub 유실 시 최대 5분(폴링 보정 주기) 지연될 수 있다.
  "규칙 생성 직후 즉시 발동"이 요구되는 시나리오는 없다.
- **`stock_id`가 NULL인 룰은 여전히 평가되지 않는다.** `alert_rules.stock_id`는 nullable인데
  현재 `fetchRulesForStock`은 `WHERE stock_id = ?`라 NULL 룰을 절대 읽지 않는다.
  인덱스로 옮겨도 같다. **이건 이 ADR이 고치는 문제가 아니라 드러내는 문제다** —
  NULL이 "전 종목 대상"을 의도한 것인지, 그냥 쓰이지 않는 컬럼 제약인지 확인이 필요하다.
  [engineering-backlog.md](../engineering-backlog.md)에 항목으로 남긴다.
- **`notify.commands` 토픽과 발송 워커가 추가된다.** 새 서비스를 만들지 않고
  `worker-alert`의 `WORKER_ROLE=notify` 변형으로 시작한다
  (기존 role gating 패턴, [ADR-022](022-tick-consumer-msa-role-gating.md)).
- **기동 시 전체 룰 로드가 필요하다.** 룰이 수백만 건이 되면 기동이 느려진다.
  `NewsBloomFilter`가 `@PostConstruct`에서 90일치를 로드하는 것과 같은 패턴이고,
  같은 한계를 갖는다. 로드는 `@Async`로 돌리고, 로드 완료 전에는 **DB 폴백 경로**로
  평가한다(현재 코드 그대로) — 그래야 기동 중 알림이 유실되지 않는다.
- **`role=all`(모놀리스) 모드에서도 같은 인덱스가 필요하다.** `TickKafkaConsumer`가
  Spring Event로 `AlertEvaluator`를 호출하는 경로도 같은 인덱스를 쓰도록 한다 —
  두 모드의 동작이 갈리면 안 된다.

## Revisit When

- **활성 룰이 워커 힙에 들어가지 않을 때** — 종목 샤딩으로 나누거나, 인덱스를
  Redis 자료구조(정렬 셋)로 외부화한다. 후자는 틱당 네트워크 왕복이 생기므로
  샤딩을 먼저 검토한다.
- **룰 타입이 늘어 이진 탐색으로 표현되지 않는 조건이 생길 때**(예: 복합 조건,
  기술적 지표 기반) — 그건 사실상 Quant Lab의 Rule Engine과 같은 문제다.
  두 엔진을 합칠지 판단해야 한다([ADR-024](024-quant-lab-forward-test.md) 관련).
- **`stock_id` NULL 룰(전 종목 대상)을 실제로 지원하게 될 때** — 종목별 인덱스에
  담을 수 없으므로 별도의 "글로벌 룰" 리스트가 필요하다.
