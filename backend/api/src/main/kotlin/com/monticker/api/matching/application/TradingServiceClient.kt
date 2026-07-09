package com.monticker.api.matching.application

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.time.Duration

/**
 * MSA 모드에서 trading-service로 HTTP 요청을 위임하는 프록시 클라이언트.
 *
 * trading.service.url 이 설정된 경우 외부 trading-service로 포워딩하고,
 * 설정되지 않은 경우(단일 프로세스 모드)에는 null을 반환해
 * 컨트롤러가 로컬 MatchingService를 직접 호출한다.
 *
 * Circuit Breaker "tradingService": OPEN 시 즉시 null 반환 → 로컬 폴백.
 * 타임아웃 누적으로 인한 api 쓰레드 풀 고갈을 방지한다.
 */
@Component
class TradingServiceClient(
    @Value("\${trading.service.url:}") private val baseUrl: String,
    restTemplateBuilder: RestTemplateBuilder,
    cbRegistry: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cb = cbRegistry.circuitBreaker("tradingService")

    private val restTemplate: RestTemplate = restTemplateBuilder
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(10))
        .build()

    val isEnabled get() = baseUrl.isNotBlank()

    fun <T> post(path: String, body: Any, responseType: Class<T>, userId: Long? = null): T? {
        if (!isEnabled) return null
        return execute("[TradingServiceClient] POST $path") {
            restTemplate.postForObject("$baseUrl$path", HttpEntity(body, headers(userId)), responseType)
        }
    }

    fun <T> get(path: String, responseType: ParameterizedTypeReference<T>, userId: Long? = null): T? {
        if (!isEnabled) return null
        return execute("[TradingServiceClient] GET $path") {
            restTemplate.exchange("$baseUrl$path", HttpMethod.GET, HttpEntity<Unit>(headers(userId)), responseType).body
        }
    }

    fun <T> delete(path: String, responseType: Class<T>, userId: Long? = null): T? {
        if (!isEnabled) return null
        return execute("[TradingServiceClient] DELETE $path") {
            restTemplate.exchange("$baseUrl$path", HttpMethod.DELETE, HttpEntity<Unit>(headers(userId)), responseType).body
        }
    }

    private fun <T> execute(label: String, block: () -> T?): T? =
        try {
            cb.executeCallable { block() }
        } catch (e: CallNotPermittedException) {
            log.warn("{} 차단됨 (Circuit OPEN)", label)
            null
        } catch (e: Exception) {
            log.error("{} 실패: {}", label, e.message)
            null
        }

    private fun headers(userId: Long?): HttpHeaders = HttpHeaders().apply {
        userId?.let { set("X-User-Id", it.toString()) }
    }
}
