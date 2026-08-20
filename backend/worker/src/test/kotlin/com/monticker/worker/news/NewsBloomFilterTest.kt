package com.monticker.worker.news

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/**
 * NewsCollector의 뉴스 중복 방지(dedup) 1차 관문인 BloomFilter 로직 자체를 검증한다.
 * NewsCollectorTest는 이 클래스를 목으로 대체하므로, 실제 mightContain/put 동작은
 * 여기서만 검증된다. filter 필드는 생성자 즉시 초기화되므로 @PostConstruct(init())
 * 없이도(즉 DB 접근 없이도) 순수하게 테스트할 수 있다.
 */
class NewsBloomFilterTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val bloomFilter = NewsBloomFilter(jdbc)

    @Test
    fun `등록되지 않은 URL은 mightContain이 false를 반환한다`() {
        assertThat(bloomFilter.mightContain("https://news.example.com/never-added")).isFalse()
    }

    @Test
    fun `put으로 등록한 URL은 이후 mightContain이 true를 반환한다`() {
        bloomFilter.put("https://news.example.com/article-1")

        assertThat(bloomFilter.mightContain("https://news.example.com/article-1")).isTrue()
    }

    @Test
    fun `stats는 mightContain 호출 결과에 따라 hit-miss 카운트를 누적한다`() {
        bloomFilter.put("https://news.example.com/seen")

        bloomFilter.mightContain("https://news.example.com/seen")   // hit
        bloomFilter.mightContain("https://news.example.com/unseen") // miss

        assertThat(bloomFilter.stats()).isEqualTo("hits=1 misses=1")
    }
}
