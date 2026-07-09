package com.monticker.api.quant.application

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
 * MSA 모드에서 quant-engine 서비스로 HTTP 요청을 위임하는 프록시 클라이언트.
 *
 * quant.engine.url 이 설정된 경우 외부 quant-engine으로 요청을 포워딩하고,
 * 설정되지 않은 경우(단일 프로세스 모드)에는 null을 반환해
 * 컨트롤러가 로컬 서비스를 직접 호출한다.
 *
 * Circuit Breaker "quantEngine": OPEN 시 즉시 null 반환 → 로컬 폴백.
 * backtest는 readTimeout 30초로 길어 OPEN 전환 기준을 빠르게 설정.
 */
@Component
class QuantEngineClient(
    @Value("\${quant.engine.url:}") private val baseUrl: String,
    restTemplateBuilder: RestTemplateBuilder,
    cbRegistry: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cb = cbRegistry.circuitBreaker("quantEngine")

    private val restTemplate: RestTemplate = restTemplateBuilder
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    val isEnabled get() = baseUrl.isNotBlank()

    fun <T> get(path: String, responseType: ParameterizedTypeReference<T>, userId: Long? = null): T? {
        if (!isEnabled) return null
        return execute("[QuantEngineClient] GET $path") {
            restTemplate.exchange("$baseUrl$path", HttpMethod.GET, HttpEntity<Unit>(headers(userId)), responseType).body
        }
    }

    fun <T> get(path: String, responseType: Class<T>, userId: Long? = null): T? {
        if (!isEnabled) return null
        return execute("[QuantEngineClient] GET $path") {
            restTemplate.exchange("$baseUrl$path", HttpMethod.GET, HttpEntity<Unit>(headers(userId)), responseType).body
        }
    }

    fun <T> post(path: String, body: Any, responseType: Class<T>, userId: Long? = null): T? {
        if (!isEnabled) return null
        return execute("[QuantEngineClient] POST $path") {
            restTemplate.postForObject("$baseUrl$path", HttpEntity(body, headers(userId)), responseType)
        }
    }

    fun <T> delete(path: String, responseType: Class<T>, userId: Long? = null): T? {
        if (!isEnabled) return null
        return execute("[QuantEngineClient] DELETE $path") {
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
