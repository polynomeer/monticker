# ADR-046: 이벤트 감지기 상태를 Redis에서 프로세스 메모리로 — 틱당 Redis 왕복 제거

## Status
Accepted

## Context

[ADR-044](044-alert-rule-in-memory-index.md)를 적용한 뒤 L-03(틱 폭주)을 다시 쟀는데
**처리량이 전혀 늘지 않았다** — 여전히 ~600 tick/s. ADR-044의 가설("틱당 알림 룰 DB 조회가
주원인")은 현재 룰 규모(10개)에서는 틀렸다. 실제 병목을 찾으려고 부하 중 워커 컨슈머 스레드의
스택을 15회 샘플링했다:

```
13/15  jdk.internal.misc.Unsafe.park ← CompletableFuture.get ← io.lettuce.core.protocol.AsyncCommand.await
       ← LettuceStringCommands.set ← PriceSpikeDetector.detect ← EventDetector.detect ← TickKafkaConsumer.onTick
```

컨슈머 스레드가 대부분의 시간을 **동기 Redis 명령을 기다리며** 보낸다. 틱 1건의 경로를 세어 보니:

| 단계 | Redis 왕복 |
|------|-----------|
| `RedisTickWriter.write` — 시세 SET (API가 읽는다) | 1 |
| `PriceSpikeDetector.detect` — prev GET, prev SET, ema GET, ema SET | 4 |
| `VolumeSurgeDetector.detect` — ema GET, ema SET | 2 |
| **합계** | **7** |

로컬 Redis 왕복 ~0.2ms × 7 ≈ 1.4ms/tick ≈ **700 tick/s** — 측정된 상한과 일치한다.
감지기가 EMA 상태(직전 가격, 변동률 EMA, 거래량 EMA)를 Redis에 두고 틱마다 읽고 쓴 것이다.

Redis에 둔 원래 이유는 "재시작 시 상태 유지"와 "여러 워커 인스턴스 간 공유"였을 것이다. 그러나:
- **같은 종목은 항상 같은 파티션/컨슈머 스레드로 온다**(Kafka 키 = stockId, [ADR-040](040-kafka-topic-declaration.md)).
  다른 인스턴스가 같은 종목의 상태를 볼 일이 없다 — 공유가 필요 없다.
- 재시작 시 잃는 것은 EMA 워밍업이다. α=0.1 EMA는 ~20틱(현재 1틱/초 기준 20초)이면 자리를 잡는다.
  감지기는 "평소 대비 3배 변동"을 보는 것이라 워밍업 동안 오탐이 아니라 미탐이 생기고, 그 창은 짧다.

후보:
- **A) 메모리 상태** — `ConcurrentHashMap<symbol, State>`. Redis 왕복 6회 제거.
- B) Redis 유지 + 파이프라이닝/비동기 — 왕복은 줄지만 코드가 복잡해지고 여전히 네트워크 의존.
- C) Redis 유지 + 주기적 스냅샷(메모리 캐시 + N초마다 SET) — A의 이점 + 재시작 복구. 지금은 필요 없는 복잡도.

## Decision

**A안.** `PriceSpikeDetector`·`VolumeSurgeDetector`의 상태를 프로세스 메모리에 둔다. Redis 의존을 제거한다.
테스트는 Redis 목 대신 `seed(symbol, ...)`로 직전 상태를 심는다.

함께 한 것 (같은 측정에서 드러난 것들):
1. **`LatencyTracker`의 틱당 WARN을 10초 1회로 제한** — 백로그 상황에서 60초에 133,749줄이 찍히며
   처리량이 절반으로 떨어지는 자기강화 루프(밀림 → 매 틱 경고 → 더 밀림)를 확인했다.
   `tick_pipeline_slow_total` 카운터로 추이를 본다.
2. **Go market-gateway `BatchTimeout` 1s → 10ms** — kafka-go `Writer`는 동기라 배치(100건)가 차거나
   타임아웃이 지나야 반환한다. 파티션이 여러 개면 파티션당 배치가 안 차서 매 호출이 1초를 기다리고,
   종목당 고루틴 1개인 구조에서 처리량이 종목당 1 msg/s로 붕괴한다. ADR-040으로 파티션을 늘린 순간
   운영에서도 터졌을 결함이다.
3. **워커 컨슈머 동시성** `spring.kafka.listener.concurrency`(기본 4) — 파티션 수까지 병렬. 종목별 상태가
   전부 파티션 단위라 스레드 간 공유가 없다(이 ADR이 그 전제를 만든다).

## Reasons

- **측정이 가리킨 곳을 고쳤다.** ADR-044는 코드를 읽고 세운 가설이었고 틀렸다. 스택 샘플링은
  15회 중 13회가 한 곳을 가리켰다. 성능 작업은 프로파일 없이 하면 안 된다 — 이 ADR의 첫 교훈.
- **상태 공유가 애초에 필요 없었다.** 파티션 키 설계가 이미 "종목 → 단일 소유자"를 보장한다.
  Redis는 이 보장을 몰라서 낸 비용이었다.
- **C안을 지금 하지 않는 이유**: 워밍업 20초를 없애려고 스냅샷 경로를 추가하는 건 아직 정당화되지
  않는다. 재시작이 잦아지거나 워밍업 미탐이 실제 불만이 되면 그때 넣는다.

## Consequences

- **L-03 결과** ([resilience-plan §5.5](../resilience-plan.md)): 단일 스레드 **600 → ~2,000 tick/s**
  (2,000/s 유입에서 랙 0·p99 200ms), 동시성 4에서 **~5,000 tick/s**. 기준선 대비 8배.
  ADR-040의 "파티션당 ~2,000 msg/s" 가정은 이 수정 후에는 맞는 숫자가 됐다.
- **재시작·리밸런스 직후 ~20틱 동안 감지가 비활성**이다(EMA 워밍업). 이전엔 Redis 상태가 남아
  즉시 감지했다. 감지 미탐 창이 매우 짧고 사용자에게 보이는 이벤트가 몇 초 늦는 정도다.
- **파티션 리밸런스로 종목이 다른 스레드/인스턴스로 옮겨가면 상태가 새로 시작**된다. 같은 워밍업 비용.
  [scale-out-plan §6.2.3](../scale-out-plan.md)의 CandleAggregator 상태 논의와 같은 성격이다.
- 남은 틱당 Redis 왕복은 시세 SET 1회다(API 조회용). 다음 상한은 여기와 JSON 파싱·캔들 집계다 —
  Kafka poll 단위 파이프라이닝으로 갈 수 있으나 지금은 하지 않는다.
- `detector:*` Redis 키는 더 이상 쓰이지 않는다. 남은 키는 TTL이 없어 수동 정리 대상이다
  (`redis-cli --scan --pattern 'detector:*' | xargs redis-cli DEL`).

## Revisit When

- 재시작·리밸런스 후 워밍업 미탐이 실제 불만이 될 때 → C안(주기적 스냅샷).
- 틱당 처리 상한이 다시 문제가 될 때 → 스택 샘플링부터. 지금 예상 병목은 시세 SET과 JSON 파싱이다.
- 감지기가 늘어 종목당 상태가 커질 때 → 메모리 사용량(`jvm_memory_used_bytes`)을 본다.
