package com.monticker.api.common.cache

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.CacheErrorHandler
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
 *   stock-score        1시간  — 밸류에이션 백분위 계산 시 전체 종목 스캔 비용 절감 (원본 데이터도 일 단위 갱신)
 *
 * 기본 직렬화: GenericJackson2JsonRedisSerializer (타입 정보 포함)
 * → Redis 에서 inspect 가능하며 역직렬화 시 클래스 정보 보존.
 *
 * 주의:
 * 1) 인자 없는 GenericJackson2JsonRedisSerializer()는 KotlinModule이 등록되지
 *    않은 자체 ObjectMapper를 사용해 Kotlin data class 역직렬화 시
 *    InvalidDefinitionException(no Creators)이 발생한다. 반드시 Spring이 관리하는
 *    (KotlinModule 등록된) ObjectMapper를 주입해서 사용해야 한다.
 * 2) ObjectMapper를 받는 생성자는 default typing을 자동으로 켜주지 않는다.
 *    무인자 생성자와 달리 activateDefaultTyping을 직접 호출하지 않으면 값에
 *    `@class` 타입 힌트가 실리지 않아, 캐시 조회 시 LinkedHashMap으로
 *    역직렬화되어 ClassCastException이 발생한다.
 */
@Configuration
@EnableCaching
class CacheConfig(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : CachingConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Redis 장애 시 @Cacheable이 예외를 던지지 않고 캐시 미스처럼 동작하게 한다 (P0-1).
     * 이게 없으면 Redis가 죽는 순간 스크리너·종목 스코어·패턴 조회가 전부 500이 된다 —
     * 캐시가 원본(DB)보다 먼저 죽으면 안 된다. 실패는 카운터로 남겨 추이를 본다.
     */
    override fun errorHandler(): CacheErrorHandler = object : CacheErrorHandler {
        private fun record(op: String, cache: Cache, e: RuntimeException) {
            meterRegistry.counter("redis_command_failed_total", "op", "cache_$op", "policy", "open").increment()
            log.warn("[CacheErrorHandler] {} 실패 cache={} — {}", op, cache.name, e.message)
        }
        override fun handleCacheGetError(e: RuntimeException, cache: Cache, key: Any) = record("get", cache, e)
        override fun handleCachePutError(e: RuntimeException, cache: Cache, key: Any, value: Any?) = record("put", cache, e)
        override fun handleCacheEvictError(e: RuntimeException, cache: Cache, key: Any) = record("evict", cache, e)
        override fun handleCacheClearError(e: RuntimeException, cache: Cache) = record("clear", cache, e)
    }

    companion object {
        const val SCREENER            = "screener"
        const val REGIME              = "regime"
        const val PATTERN             = "pattern"
        const val PORTFOLIO_OPTIMIZER = "portfolio-optimizer"
        const val STOCK_SCORE         = "stock-score"
    }

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager {
        // Kotlin data class는 기본적으로 final이라 NON_FINAL 전략으로는 루트 객체에
        // 타입 정보가 실리지 않는다(따라서 EVERYTHING을 사용해 항상 @class를 남긴다).
        val redisMapper = objectMapper.copy().activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.EVERYTHING,
            JsonTypeInfo.As.PROPERTY,
        )
        val jsonSerializer = GenericJackson2JsonRedisSerializer(redisMapper)
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
            .withCacheConfiguration(STOCK_SCORE,         config(Duration.ofHours(1)))
            .build()
    }
}
