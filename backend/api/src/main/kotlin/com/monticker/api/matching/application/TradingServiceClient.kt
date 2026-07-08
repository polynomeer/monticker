package com.monticker.api.matching.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
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
 */
@Component
class TradingServiceClient(
    @Value("\${trading.service.url:}") private val baseUrl: String,
    restTemplateBuilder: RestTemplateBuilder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restTemplate: RestTemplate = restTemplateBuilder
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(10))
        .build()

    val isEnabled get() = baseUrl.isNotBlank()

    fun <T> post(path: String, body: Any, responseType: Class<T>): T? {
        if (!isEnabled) return null
        return try {
            restTemplate.postForObject("$baseUrl$path", body, responseType)
        } catch (e: Exception) {
            log.error("[TradingServiceClient] POST {} 실패: {}", path, e.message)
            null
        }
    }

    fun <T> get(path: String, responseType: ParameterizedTypeReference<T>): T? {
        if (!isEnabled) return null
        return try {
            restTemplate.exchange("$baseUrl$path", HttpMethod.GET, null, responseType).body
        } catch (e: Exception) {
            log.error("[TradingServiceClient] GET {} 실패: {}", path, e.message)
            null
        }
    }

    fun <T> delete(path: String, responseType: Class<T>): T? {
        if (!isEnabled) return null
        return try {
            restTemplate.exchange("$baseUrl$path", HttpMethod.DELETE, HttpEntity.EMPTY, responseType).body
        } catch (e: Exception) {
            log.error("[TradingServiceClient] DELETE {} 실패: {}", path, e.message)
            null
        }
    }
}
