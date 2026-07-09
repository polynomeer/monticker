package com.monticker.worker.news

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * 뉴스 URL 중복 체크용 Bloom Filter.
 *
 * DB INSERT 전 확률적 중복 판별로 불필요한 DB 왕복을 제거한다.
 * - 예상 URL 수: 종목 5,000개 × URL 400개 = 2,000,000
 * - False Positive Rate: 1% (실제 중복 없는 URL을 중복이라 판단할 확률)
 *   → FP가 발생해도 DB INSERT를 건너뛸 뿐, 데이터 손실이 아닌 뉴스 누락에 그침
 *
 * 초기화: 기동 시 최근 90일치 URL을 DB에서 로드 (워커 재시작 후 중복 삽입 방지)
 * 배포 고려: 단일 인스턴스 보장(@DistributedLock)이 있어 in-memory로 충분하다.
 *            레플리카 공유가 필요하면 Redis BF.ADD/BF.EXISTS로 교체.
 */
@Component
class NewsBloomFilter(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val filter: BloomFilter<String> = BloomFilter.create(
        Funnels.stringFunnel(StandardCharsets.UTF_8),
        2_000_000L,   // 예상 삽입 수
        0.01,          // FPP 1%
    )

    private val hitCount  = AtomicLong(0)
    private val missCount = AtomicLong(0)

    @PostConstruct
    fun init() {
        val count = jdbc.query(
            "SELECT url FROM news_articles WHERE created_at > now() - interval '90 days'"
        ) { rs, _ -> rs.getString("url") }
            .onEach { filter.put(it) }
            .size

        log.info("[BloomFilter:news] 초기화 완료 — {}개 URL 로드, 예상 FPP=1%", count)
    }

    /** 이미 존재할 가능성이 있으면 true. true여도 DB에 실제로 없을 수 있음(FP). */
    fun mightContain(url: String): Boolean {
        val result = filter.mightContain(url)
        if (result) hitCount.incrementAndGet() else missCount.incrementAndGet()
        return result
    }

    /** DB INSERT 성공 후 필터에 등록 */
    fun put(url: String) = filter.put(url)

    fun stats() = "hits=${hitCount.get()} misses=${missCount.get()}"
}
