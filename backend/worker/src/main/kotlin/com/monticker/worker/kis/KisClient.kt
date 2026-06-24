package com.monticker.worker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

data class KisPrice(
    val symbol: String,
    val price: java.math.BigDecimal,
    val volume: Long,
    val high: java.math.BigDecimal,
    val low: java.math.BigDecimal,
    val open: java.math.BigDecimal,
)

@Component
class KisClient(
    @Value("\${kis.app-key:}") private val appKey: String,
    @Value("\${kis.app-secret:}") private val appSecret: String,
    private val cbRegistry: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper()
    private val BASE = "https://openapi.koreainvestment.com:9443"

    val isConfigured: Boolean get() = appKey.isNotBlank() && appSecret.isNotBlank()

    private var accessToken: String? = null
    private var tokenExpiry: Instant = Instant.EPOCH

    @Synchronized
    fun getAccessToken(): String? {
        if (!isConfigured) return null
        if (accessToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(300))) {
            return accessToken
        }
        return issueToken()
    }

    private fun issueToken(): String? {
        return try {
            val body = mapper.writeValueAsString(mapOf(
                "grant_type" to "client_credentials",
                "appkey"     to appKey,
                "appsecret"  to appSecret,
            ))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$BASE/oauth2/tokenP"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("KIS token issue failed: status={}", res.statusCode())
                return null
            }
            val root = mapper.readTree(res.body())
            val token = root["access_token"]?.asText() ?: return null
            accessToken = token
            tokenExpiry = Instant.now().plusSeconds(root["expires_in"]?.asLong() ?: 86400L)
            log.info("KIS access token issued, expires at {}", tokenExpiry)
            token
        } catch (e: Exception) {
            log.error("KIS token issue error: {}", e.message)
            null
        }
    }

    fun fetchPrice(symbol: String): KisPrice? {
        val cb = cbRegistry.circuitBreaker("kisApi")
        return cb.executeCallable {
            fetchPriceInternal(symbol)
        }
    }

    private fun fetchPriceInternal(symbol: String): KisPrice? {
        val token = getAccessToken() ?: return null
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$BASE/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$symbol"))
                .header("authorization", "Bearer $token")
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST01010100")
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null
            val root = mapper.readTree(res.body())
            if (root["rt_cd"]?.asText() != "0") return null
            val out = root["output"] ?: return null
            KisPrice(
                symbol = symbol,
                price  = out["stck_prpr"]?.asText()?.toBigDecimalOrNull() ?: return null,
                volume = out["acml_vol"]?.asText()?.toLongOrNull() ?: 0L,
                high   = out["stck_hgpr"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                low    = out["stck_lwpr"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                open   = out["stck_oprc"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            )
        } catch (e: Exception) {
            log.error("KIS fetchPrice failed for {}: {}", symbol, e.message)
            null
        }
    }
}
