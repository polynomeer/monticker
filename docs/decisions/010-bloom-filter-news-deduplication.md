# ADR-010: Guava Bloom Filter for News URL Deduplication

## Status
Accepted

## Context

`NewsCollector`는 30분마다 활성 종목별 뉴스를 Naver News API에서 조회하고 `news_articles` 테이블에 INSERT한다.  
테이블에 `ON CONFLICT (url) DO NOTHING`이 있어 중복 삽입은 방지되지만,  
**중복 여부를 확인하기 위한 INSERT 시도 자체가 DB 왕복을 유발**한다.

종목 5,000개 × 기사 5건 = 수집 주기당 최대 **25,000번**의 DB 왕복이 발생한다.  
대부분이 이미 저장된 URL이므로 이 왕복의 대부분은 낭비다.

SELECT로 먼저 존재 여부를 확인하는 방법도 있지만 역시 같은 횟수의 DB 조회가 필요하다.

## Decision

Guava `BloomFilter<String>`을 인-메모리 사전 필터로 사용한다.

```kotlin
private val filter = BloomFilter.create(
    Funnels.stringFunnel(StandardCharsets.UTF_8),
    2_000_000L,  // 예상 삽입 수
    0.01,        // FPP 1%
)
```

- 기동 시 `@PostConstruct`에서 최근 90일치 URL을 DB에서 로드해 필터를 시드한다.
- 수집 루프에서 `mightContain()` → true면 DB 접근 없이 건너뜀
- INSERT 성공 후 `put()`으로 필터에 등록

```kotlin
if (bloomFilter.mightContain(item.link)) { skipped++; continue }
if (persist(stockId, item)) { total++ }
```

## Reasons

- **False Negative 없음**: Bloom Filter는 실제 없는 URL을 "없다"고 반환한다는 것을 보장한다. 신규 URL을 절대 놓치지 않는다.
- **FPP 1%**: False Positive(실제 없는 URL을 "있다"고 판단)는 DB INSERT를 건너뛰는 정도의 영향이다. 기사 누락이 발생하지만 데이터 손실이 아니며 다음 수집 주기에 재시도된다.
- **메모리 효율**: 2M URL, FPP 1% 기준 약 2.4 MB — JVM 힙 관점에서 무시할 수 있는 크기다.
- **현재 아키텍처와 정합성**: `@DistributedLock`으로 단일 인스턴스 실행이 보장되므로 인스턴스 간 필터 불일치 문제가 없다.
- **운영 단순성**: Redis Bloom Filter(`BF.ADD`)는 인프라 의존성을 추가한다. 현재 규모에서 in-memory가 충분하다.

## Consequences

- **재시작 시 재로드**: JVM 재시작마다 `@PostConstruct`에서 DB를 다시 읽는다. 90일치 URL이 많아지면 기동 시간에 영향을 줄 수 있다.
- **90일 이전 URL**: 90일보다 오래된 URL은 필터에 없어 INSERT가 시도되지만 `ON CONFLICT DO NOTHING`으로 무시된다. 비효율이지만 문제가 될 규모는 아니다.
- **Worker 수평 확장 제한**: 복수 Worker 인스턴스가 각자 독립 필터를 유지하면 중복 INSERT가 발생할 수 있다. `@DistributedLock`이 이 문제를 현재 방지하고 있다.

## Revisit When

- Worker 인스턴스를 `@DistributedLock` 없이 수평 확장해야 할 때 → Redis Stack의 `BF.ADD` / `BF.EXISTS`로 교체해 분산 필터를 공유한다.
- 기동 시간이 문제가 될 때 → 필터 상태를 Redis에 스냅샷으로 저장하고 기동 시 로드한다.
- FPP 1%로 인한 기사 누락이 측정 가능한 수준에 도달할 때 → FPP를 낮추거나(메모리 증가) 로깅을 강화해 모니터링한다.
