package com.monticker.worker.toss

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Toss 플랫폼 앱(시세 전용, BYOK 아님) OAuth2 client_credentials 토큰 발급.
 *
 * backend/api의 TossBrokerageClient.issueToken()과 로직은 같지만 별도 JVM이라 클래스를
 * 공유할 수 없어(ADR-029/030과 같은 제약) 여기 최소 형태로 다시 둔다. WebSocket 인증은
 * handshake 1회뿐이라(연결 유지 중 토큰 만료돼도 끊기지 않음, ADR-031) 이 토큰은 새
 * 연결을 열 때만 유효하면 된다.
 */
@Component
class TossTokenIssuer(
    @Value("\${toss.platform.app-key:}") private val appKey: String,
    @Value("\${toss.platform.app-secret:}") private val appSecret: String,
    @Value("\${toss.platform.base-url:https://openapi.tossinvest.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper()
    private val token = AtomicReference<String?>()
    private val tokenExpiry = AtomicReference(Instant.EPOCH)

    val isConfigured: Boolean get() = appKey.isNotBlank() && appSecret.isNotBlank()

    @Synchronized
    fun getAccessToken(): String? {
        if (!isConfigured) return null
        val current = token.get()
        if (current != null && Instant.now().isBefore(tokenExpiry.get())) return current
        return issueToken()
    }

    private fun issueToken(): String? {
        return try {
            val form = listOf(
                "grant_type" to "client_credentials",
                "client_id" to appKey,
                "client_secret" to appSecret,
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }

            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("Toss platform token failed: status={}", res.statusCode())
                return null
            }
            val root = mapper.readTree(res.body())
            val accessToken = root["access_token"]?.asText() ?: return null
            val expiresIn = root["expires_in"]?.asLong() ?: 3600L
            token.set(accessToken)
            // 만료 60초 전부터는 갱신 대상으로 취급 — 발급 직후 바로 다음 호출에서 임박 판정되는 걸 방지.
            tokenExpiry.set(Instant.now().plusSeconds((expiresIn - 60).coerceAtLeast(60)))
            log.info("Toss platform access token issued (expiresIn={}s)", expiresIn)
            accessToken
        } catch (e: Exception) {
            log.error("Toss platform token error: {}", e.message)
            null
        }
    }
}
