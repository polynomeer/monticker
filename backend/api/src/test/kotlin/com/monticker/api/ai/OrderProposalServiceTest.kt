package com.monticker.api.ai

import com.anthropic.client.AnthropicClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.common.config.AnthropicConfig
import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.news.application.NewsService
import com.monticker.api.stock.application.StockService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class OrderProposalServiceTest {

    private val orderProposalRepository = mockk<OrderProposalRepository>()
    private val eventTimelineService = mockk<EventTimelineService>()
    private val newsService = mockk<NewsService>()
    private val priceActionService = mockk<PriceActionService>()
    private val stockService = mockk<StockService>()
    private val anthropicClient = mockk<AnthropicClient>()
    private val anthropicConfig = mockk<AnthropicConfig>()
    private val objectMapper = jacksonObjectMapper()

    private val service = OrderProposalService(
        orderProposalRepository, eventTimelineService, newsService,
        priceActionService, stockService, anthropicClient, anthropicConfig, objectMapper,
    )

    private fun proposal(status: OrderProposalStatus, expiresAt: Instant) = OrderProposal(
        id = 1, userId = 1L, stockId = 2L, side = OrderProposalSide.BUY,
        reasoning = "테스트 근거", status = status, expiresAt = expiresAt,
    )

    // ── LLM 응답 파싱 ────────────────────────────────────────────────────────

    @Test
    fun `정상 JSON 응답을 파싱한다`() {
        val result = service.parseLlmResponse("""{"side": "BUY", "reasoning": "가격이 저점 대비 반등"}""")
        assertThat(result).isEqualTo(LlmProposal("BUY", "가격이 저점 대비 반등"))
    }

    @Test
    fun `마크다운 코드블록으로 감싼 JSON도 파싱한다`() {
        val result = service.parseLlmResponse("```json\n{\"side\": \"SELL\", \"reasoning\": \"과열 신호\"}\n```")
        assertThat(result).isEqualTo(LlmProposal("SELL", "과열 신호"))
    }

    @Test
    fun `JSON이 없으면 null을 반환한다`() {
        assertThat(service.parseLlmResponse("죄송하지만 답변할 수 없습니다")).isNull()
    }

    @Test
    fun `JSON 형식은 맞지만 필드가 다르면 null을 반환한다`() {
        assertThat(service.parseLlmResponse("""{"direction": "BUY"}""")).isNull()
    }

    // ── create — AI 미설정 ───────────────────────────────────────────────────

    @Test
    fun `AI가 설정되지 않았으면 제안을 생성하지 않는다`() {
        every { anthropicConfig.isConfigured } returns false

        assertThatThrownBy { service.create(1L, 2L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("AI 제안 서비스를 사용할 수 없습니다")
    }

    // ── approve/reject ───────────────────────────────────────────────────────

    @Test
    fun `PENDING 제안은 승인할 수 있다`() {
        val p = proposal(OrderProposalStatus.PENDING, Instant.now().plusSeconds(600))
        every { orderProposalRepository.findByUserIdAndId(1L, 1L) } returns p
        every { orderProposalRepository.save(p) } returns p

        val result = service.approve(1L, 1L)

        assertThat(result.status).isEqualTo(OrderProposalStatus.APPROVED)
    }

    @Test
    fun `이미 처리된 제안은 다시 승인할 수 없다`() {
        val p = proposal(OrderProposalStatus.APPROVED, Instant.now().plusSeconds(600))
        every { orderProposalRepository.findByUserIdAndId(1L, 1L) } returns p

        assertThatThrownBy { service.approve(1L, 1L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("불가")
    }

    @Test
    fun `만료된 제안은 승인할 수 없다`() {
        val p = proposal(OrderProposalStatus.PENDING, Instant.now().minusSeconds(1))
        every { orderProposalRepository.findByUserIdAndId(1L, 1L) } returns p

        assertThatThrownBy { service.approve(1L, 1L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("만료")
    }

    @Test
    fun `다른 사용자의 제안은 조회되지 않는다`() {
        every { orderProposalRepository.findByUserIdAndId(99L, 1L) } returns null

        assertThatThrownBy { service.approve(99L, 1L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PENDING 제안은 거부할 수 있다`() {
        val p = proposal(OrderProposalStatus.PENDING, Instant.now().plusSeconds(600))
        every { orderProposalRepository.findByUserIdAndId(1L, 1L) } returns p
        every { orderProposalRepository.save(p) } returns p

        val result = service.reject(1L, 1L)

        assertThat(result.status).isEqualTo(OrderProposalStatus.REJECTED)
    }
}
