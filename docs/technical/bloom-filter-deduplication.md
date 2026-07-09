# Bloom Filter — 뉴스 URL 중복 제거

## 배경

뉴스 수집 워커(`NewsCollector`)는 30분 주기로 활성 종목별 뉴스를 Naver News API에서 조회한다.  
같은 URL이 여러 번 조회되면 `ON CONFLICT (url) DO NOTHING`으로 무시하지만, **불필요한 DB 왕복이 발생**한다.

종목 5,000개 × 기사 5건 = **요청당 최대 25,000번의 DB INSERT 시도**를 줄이는 것이 목표다.

---

## 요구사항

| 항목 | 값 |
|------|----|
| 예상 유니크 URL | 2,000,000 (종목 5,000 × URL 400) |
| 허용 False Positive Rate | 1% |
| False Negative | **절대 허용 불가** (신규 URL을 중복으로 판단 → 데이터 손실) |
| 메모리 예산 | ~3 MB (Bloom Filter 이론치) |
| 초기화 시간 | 기동 시 90일치 URL 로드, 1회성 |

Bloom Filter의 특성상 **False Negative는 발생하지 않는다.**  
실제로 없는 URL을 "있다"(False Positive)고 판단할 확률이 1%다. 이 경우 DB INSERT를 건너뛰는 정도의 영향.

---

## 구현

### 핵심 클래스

```kotlin
// backend/worker/src/main/kotlin/com/monticker/worker/news/NewsBloomFilter.kt
@Component
class NewsBloomFilter(private val jdbc: JdbcTemplate) {

    private val filter: BloomFilter<String> = BloomFilter.create(
        Funnels.stringFunnel(StandardCharsets.UTF_8),
        2_000_000L,  // expected insertions
        0.01,        // FPP
    )

    @PostConstruct
    fun init() {
        val count = jdbc.query(
            "SELECT url FROM news_articles WHERE created_at > now() - interval '90 days'"
        ) { rs, _ -> rs.getString("url") }
            .onEach { filter.put(it) }
            .size
        log.info("[BloomFilter:news] 초기화 완료 — {}개 URL 로드", count)
    }

    fun mightContain(url: String): Boolean = filter.mightContain(url)
    fun put(url: String) = filter.put(url)
}
```

### 통합 흐름

```kotlin
// NewsCollector.collect()
for (item in fetchNews(stockName)) {
    if (bloomFilter.mightContain(item.link)) {
        skipped++
        continue                    // DB 조회 없이 바로 건너뜀
    }
    if (persist(stockId, item)) {   // INSERT ON CONFLICT DO NOTHING
        bloomFilter.put(item.link)  // 성공한 경우에만 필터에 등록
        total++
    }
}
```

`persist()` 성공 후에만 `put()`을 호출한다.  
`INSERT ON CONFLICT DO NOTHING`으로 실제 중복이면 `inserted = 0`이고 `put()`도 호출되지 않는다.

---

## 의존성

```kotlin
// backend/worker/build.gradle.kts
implementation("com.google.guava:guava:33.3.1-jre")
```

---

## 성능 분석

### 메모리 사용량

Guava Bloom Filter 이론 공식:
```
m = -n × ln(p) / (ln2)²
  = -2_000_000 × ln(0.01) / 0.480
  ≈ 19,170,117 bits ≈ 2.3 MB
```

실제 사용량은 JVM 오버헤드 포함 약 3~4 MB.

### 처리량 개선 (예상)

| 시나리오 | DB 왕복 횟수 |
|----------|-------------|
| 필터 없음 (기존) | 최대 25,000회 / 주기 |
| 필터 도입 (steady-state) | ~250회 / 주기 (신규 URL 1%) |

---

## 한계 및 확장 경로

### 재시작 시 필터 리셋

JVM 재시작 시 in-memory 필터가 초기화되므로 `@PostConstruct`에서 DB를 다시 로드한다.  
로드 시간은 URL 200만 개 기준 약 2~5초(네트워크, DB 성능 의존).

### 워커 다중 인스턴스

현재 `@DistributedLock`으로 단일 인스턴스 실행을 보장하므로 인스턴스 간 필터 불일치 문제가 없다.  
레플리카를 늘릴 경우 **Redis BloomFilter**(`BF.ADD` / `BF.EXISTS`)로 교체하는 것이 자연스럽다.

```
# Redis Stack 사용 시
BF.RESERVE news:urls 0.01 2000000
BF.ADD     news:urls "https://..."
BF.EXISTS  news:urls "https://..."
```

### 90일 초과 URL

90일 이전 URL은 필터에 로드하지 않는다.  
같은 URL이 오래된 기사로 재수집되면 DB INSERT가 시도되지만 `ON CONFLICT DO NOTHING`으로 무시된다.

---

## 관련 문서

- [resilience-patterns.md](./resilience-patterns.md) — Distributed Lock (@DistributedLock)
- [worker-performance.md](./worker-performance.md) — Worker 전반적인 성능 분석
