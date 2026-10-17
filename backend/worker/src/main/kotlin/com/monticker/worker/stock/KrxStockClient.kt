package com.monticker.worker.stock

import com.monticker.worker.common.HttpTimeouts
import com.monticker.worker.common.PlainJson
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class KrxStockItem(
    val symbol: String,
    val name: String,
    val market: String,    // KOSPI | KOSDAQ
    val sector: String?,
)

@Component
class KrxStockClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = PlainJson.mapper

    fun fetchStocks(): List<KrxStockItem> {
        val kospi  = fetchMarket("STK", "KOSPI")
        val kosdaq = fetchMarket("KSQ", "KOSDAQ")
        log.info("KRX fetch: KOSPI={}, KOSDAQ={}", kospi.size, kosdaq.size)
        return kospi + kosdaq
    }

    private fun fetchMarket(mktId: String, marketName: String): List<KrxStockItem> {
        return try {
            val body = "bld=dbms%2FMDC%2FSTAT%2Fstandard%2FMDCSTAT01901" +
                "&mktId=$mktId&share=1&money=1&csvxls_isNo=false"

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0 (compatible; monticker/1.0)")
                .header("Referer", "http://data.krx.co.kr")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(HttpTimeouts.COLLECTOR_READ)
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("KRX API error: status={}", response.statusCode())
                return emptyList()
            }

            parseItems(response.body(), marketName)
        } catch (e: Exception) {
            log.error("KRX fetch failed for market {}: {}", marketName, e.message)
            emptyList()
        }
    }

    /** 응답 본문 → 종목 목록. 테스트가 실제 응답 모양으로 고정한다(B4-6) */
    internal fun parseItems(body: String, marketName: String): List<KrxStockItem> {
        val root = mapper.readTree(body)
        val items = root["OutBlock_1"] ?: return emptyList()
        return items.mapNotNull { item ->
                val symbol = item["ISU_SRT_CD"]?.asString("")?.trim() ?: return@mapNotNull null
                val name   = item["ISU_NM"]?.asString("")?.trim()     ?: return@mapNotNull null
                if (symbol.isBlank() || name.isBlank()) return@mapNotNull null
                KrxStockItem(
                    symbol = symbol,
                    name   = name,
                    market = marketName,
                    sector = item["SECT_TP_NM"]?.asString("")?.trim()?.takeIf { it.isNotBlank() },
                )
            }
    }
}
