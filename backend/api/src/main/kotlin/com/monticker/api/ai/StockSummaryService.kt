package com.monticker.api.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.monticker.api.common.config.AnthropicConfig
import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.event.domain.StockEvent
import com.monticker.api.news.application.NewsService
import com.monticker.api.news.domain.NewsArticle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class StockSummaryService(
    private val eventTimelineService: EventTimelineService,
    private val newsService: NewsService,
    private val anthropicClient: AnthropicClient,
    private val anthropicConfig: AnthropicConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getSummary(stockId: Long, stockName: String): String {
        if (!anthropicConfig.isConfigured) {
            return generateFallbackSummary(stockId)
        }

        val now = Instant.now()
        val from = now.minus(24, ChronoUnit.HOURS)

        val events = eventTimelineService.getTimeline(stockId, from, now)
        val news = newsService.getNews(stockId, 5)

        if (events.isEmpty() && news.isEmpty()) {
            return "최근 24시간 동안 주목할 만한 이벤트가 없습니다."
        }

        val prompt = buildPrompt(stockName, events, news)

        return try {
            val params = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5_20251001)
                .maxTokens(256L)
                .addUserMessage(prompt)
                .build()

            val response = anthropicClient.messages().create(params)
            response.content().stream()
                .flatMap { it.text().stream() }
                .map { it.text() }
                .findFirst()
                .orElse(generateFallbackSummary(stockId))
        } catch (e: Exception) {
            log.error("Claude API call failed for stockId={}: {}", stockId, e.message)
            generateFallbackSummary(stockId)
        }
    }

    private fun buildPrompt(
        stockName: String,
        events: List<StockEvent>,
        news: List<NewsArticle>,
    ): String {
        val eventSummary = events.joinToString("\n") { "- ${it.title} (중요도: ${it.importanceScore})" }
        val newsSummary = news.joinToString("\n") { "- ${it.title}" }

        return """
            종목명: $stockName

            최근 24시간 이벤트:
            ${eventSummary.ifBlank { "없음" }}

            최근 뉴스:
            ${newsSummary.ifBlank { "없음" }}

            위 정보를 바탕으로 "$stockName" 종목의 최근 시장 동향을 한국어로 2~3문장으로 간결하게 요약해줘.
            투자 조언은 하지 말고, 사실 기반으로만 설명해줘.
        """.trimIndent()
    }

    private fun generateFallbackSummary(stockId: Long): String =
        "현재 AI 요약 서비스를 사용할 수 없습니다. 이벤트 타임라인을 확인해주세요."
}
