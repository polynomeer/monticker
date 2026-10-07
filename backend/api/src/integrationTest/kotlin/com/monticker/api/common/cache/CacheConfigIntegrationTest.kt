package com.monticker.api.common.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.monticker.api.screener.application.ScreenerResult
import com.monticker.api.screener.domain.ScreenerItem
import com.monticker.api.screener.infrastructure.SectorPerformance
import com.monticker.api.event.application.EventDaySummary
import com.monticker.api.event.application.EventTypeCount
import com.monticker.api.event.application.StockEventCount
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal

/**
 * CacheConfig가 실제 Redis에 Kotlin data class를 직렬화/역직렬화할 때
 * 타입 정보를 잃지 않는지 검증하는 통합 테스트.
 *
 * 목: GenericJackson2JsonRedisSerializer()를 인자 없이 생성하면 KotlinModule이
 * 없는 자체 ObjectMapper를 써서 캐시를 읽을 때 InvalidDefinitionException 또는
 * LinkedHashMap ClassCastException으로 500 에러가 났다. 이 회귀를 막기 위한 테스트.
 *
 * 단위 테스트(ScreenerServiceTest 등)는 Redis를 목킹하기 때문에 이 문제를
 * 잡아내지 못한다 — 그래서 실제 Redis 컨테이너로 검증한다.
 */
@Testcontainers
class CacheConfigIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)

        private lateinit var connectionFactory: LettuceConnectionFactory

        @BeforeAll
        @JvmStatic
        fun setUpConnectionFactory() {
            connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
            connectionFactory.afterPropertiesSet()
        }

        @AfterAll
        @JvmStatic
        fun tearDownConnectionFactory() {
            connectionFactory.destroy()
        }
    }

    @Test
    fun `Kotlin data class round-trips through the real Redis cache without losing type info`() {
        // 실제 Spring 컨테이너는 @Bean으로 반환된 RedisCacheManager가
        // InitializingBean이므로 afterPropertiesSet()을 호출해 withCacheConfiguration
        // 으로 등록한 이름별 설정을 캐시에 미리 로드한다(AbstractCacheManager.loadCaches).
        // 컨테이너 밖에서 직접 생성할 때는 이걸 우리가 호출해줘야
        // getCache("screener")가 커스텀 직렬화 설정이 아닌 기본(JDK 직렬화) 설정으로
        // 폴백하지 않는다.
        val cacheManager = CacheConfig(jacksonObjectMapper(), SimpleMeterRegistry()).cacheManager(connectionFactory)
        cacheManager.afterPropertiesSet()
        val cache = cacheManager.getCache(CacheConfig.SCREENER)!!

        val original = ScreenerResult(
            items = listOf(
                ScreenerItem(
                    rank = 1,
                    stockId = 1,
                    symbol = "005930",
                    name = "삼성전자",
                    market = "KOSPI",
                    sector = "전기전자",
                    price = BigDecimal("70000"),
                    prevClose = BigDecimal("69000"),
                    changeRate = 1.5,
                    changeAmount = BigDecimal("1000"),
                    volume = 1_000_000L,
                    amount = BigDecimal("70000000000"),
                    buyRatio = 55,
                    sellRatio = 45,
                    marketCap = null,
                    per = null,
                    pbr = null,
                    isFundamentalsMocked = false,
                ),
            ),
            total = 1,
            hasMore = false,
        )

        cache.put("key", original)

        // @Cacheable이 캐시 히트 시 하는 것과 동일하게 선언된 타입으로 읽는다.
        val cached = cache.get("key", ScreenerResult::class.java)

        assertThat(cached).isEqualTo(original)
    }

    @Test
    fun `ADR-087 aggregate cache values round-trip, including nullable doubles and List results`() {
        val cacheManager = CacheConfig(jacksonObjectMapper(), SimpleMeterRegistry()).cacheManager(connectionFactory)
        cacheManager.afterPropertiesSet()

        val summary = EventDaySummary(
            date = "2026-10-07", total = 3,
            byType = listOf(EventTypeCount("PRICE_SPIKE", 2, 1), EventTypeCount("PRICE_DROP", 1, 1)),
            surgeStocks = 1, plungeStocks = 1,
        )
        cacheManager.getCache(CacheConfig.EVENT_SUMMARY)!!.put("2026-10-07", summary)
        assertThat(cacheManager.getCache(CacheConfig.EVENT_SUMMARY)!!.get("2026-10-07", EventDaySummary::class.java)).isEqualTo(summary)

        val counts = listOf(StockEventCount(1, 0), StockEventCount(2, 5_000_000_000L))
        cacheManager.getCache(CacheConfig.EVENT_COUNTS)!!.put("k", counts)
        assertThat(cacheManager.getCache(CacheConfig.EVENT_COUNTS)!!.get("k")?.get()).isEqualTo(counts)

        val sectors = listOf(
            SectorPerformance("반도체", 10, 9, 1.25, 5, 3, 1, 4),
            SectorPerformance("빈섹터", 1, 0, null, 0, 0, 0, 0),
        )
        cacheManager.getCache(CacheConfig.SECTOR_PERFORMANCE)!!.put("all", sectors)
        assertThat(cacheManager.getCache(CacheConfig.SECTOR_PERFORMANCE)!!.get("all")?.get()).isEqualTo(sectors)
    }
}
