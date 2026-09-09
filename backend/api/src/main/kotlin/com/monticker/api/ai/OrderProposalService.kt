package com.monticker.api.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.common.config.AnthropicConfig
import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.event.domain.StockEvent
import com.monticker.api.news.application.NewsService
import com.monticker.api.news.domain.NewsArticle
import com.monticker.api.stock.application.StockService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

internal data class LlmProposal(val side: String, val reasoning: String)

data class OrderProposalResponse(
    val id: Long,
    val stockId: Long,
    val side: OrderProposalSide,
    val reasoning: String,
    val status: OrderProposalStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
)

private fun OrderProposal.toResponse() = OrderProposalResponse(id, stockId, side, reasoning, status, createdAt, expiresAt)

/**
 * ADR-036 — AI 주문 제안. StockSummaryService와 같은 데이터 소스(이벤트/뉴스/가격 동향)로
 * 프롬프트를 구성하되, 자유 텍스트가 아니라 구조화된 방향(BUY/SELL/HOLD)+근거를 요청한다.
 * 수량은 절대 LLM이 정하지 않는다 — 승인 후 사용자가 주문 폼에서 직접 입력한다.
 */
@Service
class OrderProposalService(
    private val orderProposalRepository: OrderProposalRepository,
    private val eventTimelineService: EventTimelineService,
    private val newsService: NewsService,
    private val priceActionService: PriceActionService,
    private val stockService: StockService,
    private val anthropicClient: AnthropicClient,
    private val anthropicConfig: AnthropicConfig,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jsonPattern = Regex("\\{[\\s\\S]*}")
    private val proposalTtl: Duration = Duration.ofMinutes(30)

    fun create(userId: Long, stockId: Long): OrderProposalResponse {
        check(anthropicConfig.isConfigured) { "AI 제안 서비스를 사용할 수 없습니다" }
        val stock = stockService.getById(stockId)

        val now = Instant.now()
        val from = now.minus(24, ChronoUnit.HOURS)
        val events = eventTimelineService.getTimeline(stockId, from, now)
        val news = newsService.getNews(stockId, 5)
        val priceAction = priceActionService.getPriceAction(stockId, stock.symbol)

        val prompt = buildPrompt(stock.name, events, news, priceAction)
        val llmResult = callLlm(prompt)
            ?: throw IllegalStateException("AI 제안 생성에 실패했습니다. 잠시 후 다시 시도해주세요.")

        val side = runCatching { OrderProposalSide.valueOf(llmResult.side.uppercase()) }
            .getOrElse { throw IllegalStateException("AI 제안 생성에 실패했습니다. 잠시 후 다시 시도해주세요.") }

        val proposal = orderProposalRepository.save(
            OrderProposal(
                userId    = userId,
                stockId   = stockId,
                side      = side,
                reasoning = llmResult.reasoning,
                expiresAt = now.plus(proposalTtl),
            )
        )
        return proposal.toResponse()
    }

    fun approve(userId: Long, proposalId: Long): OrderProposalResponse {
        val proposal = orderProposalRepository.findByUserIdAndId(userId, proposalId)
            ?: throw NoSuchElementException("제안을 찾을 수 없습니다: $proposalId")
        proposal.approve()
        return orderProposalRepository.save(proposal).toResponse()
    }

    fun reject(userId: Long, proposalId: Long): OrderProposalResponse {
        val proposal = orderProposalRepository.findByUserIdAndId(userId, proposalId)
            ?: throw NoSuchElementException("제안을 찾을 수 없습니다: $proposalId")
        proposal.reject()
        return orderProposalRepository.save(proposal).toResponse()
    }

    fun get(userId: Long, proposalId: Long): OrderProposalResponse =
        (orderProposalRepository.findByUserIdAndId(userId, proposalId)
            ?: throw NoSuchElementException("제안을 찾을 수 없습니다: $proposalId")).toResponse()

    fun list(userId: Long, stockId: Long?): List<OrderProposalResponse> =
        (if (stockId != null) orderProposalRepository.findAllByUserIdAndStockIdOrderByCreatedAtDesc(userId, stockId)
        else orderProposalRepository.findAllByUserIdOrderByCreatedAtDesc(userId))
            .map { it.toResponse() }

    // ── LLM 호출 ─────────────────────────────────────────────────────────────

    private fun callLlm(prompt: String): LlmProposal? {
        return try {
            val params = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5_20251001)
                .maxTokens(512L)
                .addUserMessage(prompt)
                .build()

            val response = anthropicClient.messages().create(params)
            val text = response.content().stream()
                .flatMap { it.text().stream() }
                .map { it.text() }
                .findFirst()
                .orElse(null) ?: return null

            parseLlmResponse(text)
        } catch (e: Exception) {
            log.error("AI 주문 제안 LLM 호출 실패: {}", e.message)
            null
        }
    }

    internal fun parseLlmResponse(text: String): LlmProposal? {
        val json = jsonPattern.find(text)?.value ?: return null
        return try {
            objectMapper.readValue(json, LlmProposal::class.java)
        } catch (e: Exception) {
            log.warn("AI 주문 제안 응답 파싱 실패: {}", e.message)
            null
        }
    }

    private fun buildPrompt(
        stockName: String,
        events: List<StockEvent>,
        news: List<NewsArticle>,
        priceAction: PriceActionService.PriceAction?,
    ): String {
        val eventSummary = events.joinToString("\n") { "- ${it.title} (중요도: ${it.importanceScore})" }
        val newsSummary = news.joinToString("\n") { "- ${it.title}" }
        val priceSummary = priceAction?.let { p ->
            val changeLine = p.prevClose?.let { prev ->
                "전일 종가 대비: ${priceActionService.pctChange(prev, p.current)}%"
            } ?: "전일 종가 데이터 없음"
            """
            현재가: ${p.current}
            오늘 거래 범위: ${p.dayLow} ~ ${p.dayHigh} (시가 ${p.dayOpen})
            $changeLine
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

            위 정보만 바탕으로 이 종목을 지금 매수(BUY)할지, 매도(SELL)할지, 아무것도 하지 않을지(HOLD) 판단해줘.
            이것은 실제 투자자문이 아니라 모의투자 시뮬레이션 참고용 제안이라는 점을 감안해줘.
            반드시 아래 JSON 형식으로만 답하고, 그 외 다른 텍스트는 절대 포함하지 마:
            {"side": "BUY 또는 SELL 또는 HOLD 중 하나", "reasoning": "2~3문장으로 판단 근거"}
        """.trimIndent()
    }
}
