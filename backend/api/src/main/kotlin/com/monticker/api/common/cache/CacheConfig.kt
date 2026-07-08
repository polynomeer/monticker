package com.monticker.api.common.cache

import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * Redis 기반 CacheManager.
 *
 * 캐시명 → TTL 매핑:
 *   screener          5초   — 실시간 랭킹, 매 요청 SQL 재수행 방지
 *   regime            1시간  — ADX/변동성 계산 비용 절감 (장 마감 Batch 후 evict)
 *   pattern           30분  — ZigZag + 패턴 탐지 CPU 절감
 *   portfolio-optimizer 30분 — Markowitz/Kelly 행렬 연산 비용 절감
 *
 * 기본 직렬화: GenericJackson2JsonRedisSerializer (타입 정보 포함)
 * → Redis 에서 inspect 가능하며 역직렬화 시 클래스 정보 보존.
 */
@Configuration
@EnableCaching
class CacheConfig {

    companion object {
        const val SCREENER            = "screener"
        const val REGIME              = "regime"
        const val PATTERN             = "pattern"
        const val PORTFOLIO_OPTIMIZER = "portfolio-optimizer"
    }

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager {
        val jsonSerializer = GenericJackson2JsonRedisSerializer()
        val valueSerializer = RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer)
        val keySerializer   = RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())

        fun config(ttl: Duration) = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(keySerializer)
            .serializeValuesWith(valueSerializer)
            .entryTtl(ttl)
            .disableCachingNullValues()

        return RedisCacheManager.builder(connectionFactory)
            .withCacheConfiguration(SCREENER,            config(Duration.ofSeconds(5)))
            .withCacheConfiguration(REGIME,              config(Duration.ofHours(1)))
            .withCacheConfiguration(PATTERN,             config(Duration.ofMinutes(30)))
            .withCacheConfiguration(PORTFOLIO_OPTIMIZER, config(Duration.ofMinutes(30)))
            .build()
    }
}
