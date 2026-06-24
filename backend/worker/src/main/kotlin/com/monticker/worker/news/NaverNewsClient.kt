package com.monticker.worker.news

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NaverNewsItem(
    val title: String,
    val description: String,
    val link: String,
    val pubDate: String,
    val source: String,
)

@Component
class NaverNewsClient(
    @Value("\${naver.news.client-id:}") private val clientId: String,
    @Value("\${naver.news.client-secret:}") private val clientSecret: String,
    private val cbRegistry: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val pubDateFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun search(query: String, display: Int = 10): List<NaverNewsItem> {
        val cb = cbRegistry.circuitBreaker("naverNews")
        return try {
            cb.executeCallable { searchInternal(query, display) }
        } catch (e: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            log.warn("[CircuitBreaker:naverNews] OPEN — 뉴스 검색 차단")
            emptyList()
        }
    }

    private fun searchInternal(query: String, display: Int = 10): List<NaverNewsItem> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://openapi.naver.com/v1/search/news.json?query=$encoded&display=$display&sort=date"))
            .header("X-Naver-Client-Id", clientId)
            .header("X-Naver-Client-Secret", clientSecret)
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("Naver News API error: status={} body={}", response.statusCode(), response.body().take(200))
            return emptyList()
        }

        val root = mapper.readTree(response.body())
        return root["items"]?.map { item ->
            NaverNewsItem(
                title       = item["title"].asText().stripHtmlTags(),
                description = item["description"].asText().stripHtmlTags(),
                link        = item["link"].asText(),
                pubDate     = item["pubDate"].asText(),
                source      = item["originallink"].asText().extractDomain(),
            )
        } ?: emptyList()
    }

    fun parsePubDate(pubDate: String): java.time.Instant =
        ZonedDateTime.parse(pubDate, pubDateFormatter).toInstant()

    private fun String.stripHtmlTags() = replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()
    private fun String.extractDomain() = try {
        URI.create(this).host?.removePrefix("www.") ?: this
    } catch (_: Exception) { this }
}
