package com.monticker.api.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.monticker.api.common.config.AnthropicConfig
import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.event.domain.StockEvent
import com.monticker.api.marketdata.application.CandleService
import com.monticker.api.marketdata.application.MarketDataService
import com.monticker.api.news.application.NewsService
import com.monticker.api.news.domain.NewsArticle
import org.slf4j.LoggerFactory
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class StockSummaryService(
    private val eventTimelineService: EventTimelineService,
    private val newsService: NewsService,
    private val candleService: CandleService,
    private val marketDataService: MarketDataService,
    private val anthropicClient: AnthropicClient,
    private val anthropicConfig: AnthropicConfig,
    private val esOps: ElasticsearchOperations,
    private val summarySearchRepository: SummarySearchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getSummary(stockId: Long, stockName: String, symbol: String): String {
        // ES 캐시 확인 — 1시간 이내 생성된 요약이 있으면 재사용
        val cached = findCached(stockId)
        if (cached != null) {
            log.debug("Summary cache hit for stockId={}", stockId)
            return cached.summary
        }

        if (!anthropicConfig.isConfigured) {
            return generateFallbackSummary(stockId)
        }

        val now = Instant.now()
        val from = now.minus(24, ChronoUnit.HOURS)

        val events = eventTimelineService.getTimeline(stockId, from, now)
        val news = newsService.getNews(stockId, 5)
        val priceAction = getPriceAction(stockId, symbol)

        if (events.isEmpty() && news.isEmpty() && priceAction == null) {
            return "최근 24시간 동안 주목할 만한 이벤트가 없습니다."
        }

        val prompt = buildPrompt(stockName, events, news, priceAction)

        return try {
            val params = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5_20251001)
                .maxTokens(256L)
                .addUserMessage(prompt)
                .build()

            val response = anthropicClient.messages().create(params)
            val summary = response.content().stream()
                .flatMap { it.text().stream() }
                .map { it.text() }
                .findFirst()
                .orElse(generateFallbackSummary(stockId))

            saveToEs(stockId, stockName, summary)
            summary
        } catch (e: Exception) {
            log.error("Claude API call failed for stockId={}: {}", stockId, e.message)
            generateFallbackSummary(stockId)
        }
    }

    // ── ES 캐시 ──────────────────────────────────────────────────────────────

    private fun findCached(stockId: Long): SummaryDocument? {
        return try {
            val since = Instant.now().minus(1, ChronoUnit.HOURS)
            val query = NativeQuery.builder()
                .withQuery { q ->
                    q.bool { b ->
                        b.filter { f -> f.term { t -> t.field("stockId").value(stockId) } }
                        b.filter { f ->
                            f.range { r ->
                                r.date { d -> d.field("generatedAt").gte(since.toEpochMilli().toString()) }
                            }
                        }
                        b
                    }
                }
                .withMaxResults(1)
                .build()
            esOps.search(query, SummaryDocument::class.java).firstOrNull()?.content
        } catch (e: Exception) {
            log.debug("ES summary cache lookup failed: {}", e.message)
            null
        }
    }

    private fun saveToEs(stockId: Long, stockName: String, summary: String) {
        try {
            summarySearchRepository.save(
                SummaryDocument(
                    id          = stockId.toString(),
                    stockId     = stockId,
                    stockName   = stockName,
                    summary     = summary,
                    generatedAt = Instant.now(),
                )
            )
        } catch (e: Exception) {
            log.warn("ES summary save failed for stockId={}: {}", stockId, e.message)
        }
    }

    // ── 가격 동향 ────────────────────────────────────────────────────────────

    private data class PriceAction(
        val current: BigDecimal,
        val dayOpen: BigDecimal,
        val dayHigh: BigDecimal,
        val dayLow: BigDecimal,
        val prevClose: BigDecimal?,
    )

    /** 오늘자 시가·고가·저가와 전일 종가를 candles_1d에서 가져와 가격 동향을 구성한다. */
    private fun getPriceAction(stockId: Long, symbol: String): PriceAction? {
        return try {
            val latest = marketDataService.getLatestPrice(stockId, symbol) ?: return null
            val recentDaily = candleService.getCandles(
                stockId, "1d",
                from = Instant.now().minus(5, ChronoUnit.DAYS),
            ).sortedBy { it.time }
            val today = recentDaily.lastOrNull() ?: return null
            val prevClose = recentDaily.dropLast(1).lastOrNull()?.close
            PriceAction(
                current   = latest.price,
                dayOpen   = today.open,
                dayHigh   = today.high,
                dayLow    = today.low,
                prevClose = prevClose,
            )
        } catch (e: Exception) {
            log.debug("Price action lookup failed for stockId={}: {}", stockId, e.message)
            null
        }
    }

    private fun pctChange(from: BigDecimal, to: BigDecimal): String {
        if (from.signum() == 0) return "0.00"
        return to.subtract(from).divide(from, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    // ── 프롬프트 빌더 ────────────────────────────────────────────────────────

    private fun buildPrompt(
        stockName: String,
        events: List<StockEvent>,
        news: List<NewsArticle>,
        priceAction: PriceAction?,
    ): String {
        val eventSummary = events.joinToString("\n") { "- ${it.title} (중요도: ${it.importanceScore})" }
        val newsSummary = news.joinToString("\n") { "- ${it.title}" }
        val priceSummary = priceAction?.let { p ->
            val changeLine = p.prevClose?.let { prev ->
                "전일 종가 대비: ${pctChange(prev, p.current)}%"
            } ?: "전일 종가 데이터 없음"
            """
            현재가: ${p.current}
            오늘 거래 범위: ${p.dayLow} ~ ${p.dayHigh} (시가 ${p.dayOpen})
            $changeLine
            현재가는 오늘 저점 대비 ${pctChange(p.dayLow, p.current)}%, 오늘 고점 대비 ${pctChange(p.dayHigh, p.current)}% 위치
            """.trimIndent()
        } ?: "가격 데이터 없음"

        return """
            종목명: $stockName

            오늘 가격 동향:
            $priceSummary

            최근 24시간 이벤트:
            ${eventSummary.ifBlank { "없음" }}

            최근 뉴스:
            ${newsSummary.ifBlank { "없음" }}

            위 정보를 바탕으로 "$stockName" 종목의 최근 시장 동향을 한국어로 2~3문장으로 간결하게 요약해줘.
            가격 데이터가 있으면 첫 문장에서 오늘 거래 범위나 전일 대비 등락률처럼 구체적인 숫자로 가격 움직임을 먼저 설명하고,
            이어서 그 움직임과 관련 있어 보이는 이벤트·뉴스가 있다면 자연스럽게 연결해줘.
            투자 조언은 하지 말고, 사실 기반으로만 설명해줘.
        """.trimIndent()
    }

    private fun generateFallbackSummary(stockId: Long): String =
        "현재 AI 요약 서비스를 사용할 수 없습니다. 이벤트 타임라인을 확인해주세요."
}
