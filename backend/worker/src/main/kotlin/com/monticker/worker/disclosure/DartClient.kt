package com.monticker.worker.disclosure

import com.monticker.worker.common.HttpTimeouts
import com.monticker.worker.common.PlainJson
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DartDisclosure(
    val rceptNo: String,       // 접수번호
    val corpName: String,      // 회사명
    val stockCode: String,     // 종목코드
    val reportName: String,    // 보고서명
    val rceptDate: String,     // 접수일 YYYYMMDD
)

@Component
class DartClient(
    @Value("\${dart.api-key:}") private val apiKey: String,
    cbRegistry: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cb = cbRegistry.circuitBreaker("dartApi")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = PlainJson.mapper
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun fetchRecent(days: Int = 1): List<DartDisclosure> {
        if (!isConfigured) return emptyList()

        return try {
            cb.executeCallable { fetchRecentInternal(days) }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:dartApi] OPEN — 공시 수집 건너뜀")
            emptyList()
        } catch (e: Exception) {
            log.error("DART fetch failed: {}", e.message)
            emptyList()
        }
    }

    private fun fetchRecentInternal(days: Int): List<DartDisclosure> {
        val today = LocalDate.now()
        val from  = today.minusDays(days.toLong()).format(dateFmt)
        val to    = today.format(dateFmt)

        val url = "https://opendart.fss.or.kr/api/list.json" +
            "?crtfc_key=$apiKey&bgn_de=$from&end_de=$to" +
            "&last_reprt_at=Y&page_no=1&page_count=100"

        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(HttpTimeouts.COLLECTOR_READ)
            .build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) {
            log.warn("DART API error: status={}", res.statusCode())
            return emptyList()
        }

        return parseList(res.body())
    }

    /** 응답 본문 → 공시 목록. 테스트가 실제 응답 모양으로 고정한다(B4-6) */
    internal fun parseList(body: String): List<DartDisclosure> {
        val root = mapper.readTree(body)
        if (root["status"]?.asString("") != "000") {
            log.warn("DART API non-ok status: {}", root["message"]?.asString(""))
            return emptyList()
        }

        return root["list"]?.mapNotNull { item ->
            val stockCode = item["stock_code"]?.asString("")?.trim() ?: return@mapNotNull null
            if (stockCode.isBlank()) return@mapNotNull null
            DartDisclosure(
                rceptNo    = item["rcept_no"]?.asString("") ?: "",
                corpName   = item["corp_name"]?.asString("") ?: "",
                stockCode  = stockCode,
                reportName = item["report_nm"]?.asString("") ?: "",
                rceptDate  = item["rcept_dt"]?.asString("") ?: "",
            )
        } ?: emptyList()
    }
}
