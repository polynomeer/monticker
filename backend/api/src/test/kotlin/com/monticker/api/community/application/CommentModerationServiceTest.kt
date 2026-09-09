package com.monticker.api.community.application

import com.anthropic.client.AnthropicClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monticker.api.common.config.AnthropicConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommentModerationServiceTest {

    private val anthropicClient = mockk<AnthropicClient>()
    private val anthropicConfig = mockk<AnthropicConfig>()
    private val objectMapper = jacksonObjectMapper()

    private val service = CommentModerationService(anthropicClient, anthropicConfig, objectMapper)

    @Test
    fun `명백한 매수 권유 키워드는 AI 호출 없이 즉시 차단된다`() {
        val result = service.check("이 종목 지금 매수하세요!")

        assertThat(result).isInstanceOf(ModerationResult.Blocked::class.java)
    }

    @Test
    fun `매도 권유 키워드도 즉시 차단된다`() {
        val result = service.check("고민 말고 파세요")

        assertThat(result).isInstanceOf(ModerationResult.Blocked::class.java)
    }

    @Test
    fun `AI가 설정되지 않았으면 fail-closed로 차단된다`() {
        every { anthropicConfig.isConfigured } returns false

        val result = service.check("오늘 실적 발표가 기대되네요")

        assertThat(result).isInstanceOf(ModerationResult.Blocked::class.java)
    }

    // ── AI 응답 파싱 ─────────────────────────────────────────────────────────

    @Test
    fun `정상 JSON 응답을 파싱한다`() {
        val result = service.parseJudgement("""{"isRecommendation": false, "reason": "단순 의견"}""")
        assertThat(result).isEqualTo(ModerationJudgement(false, "단순 의견"))
    }

    @Test
    fun `마크다운 코드블록으로 감싼 JSON도 파싱한다`() {
        val result = service.parseJudgement("```json\n{\"isRecommendation\": true, \"reason\": \"직접적 권유\"}\n```")
        assertThat(result).isEqualTo(ModerationJudgement(true, "직접적 권유"))
    }

    @Test
    fun `JSON이 없으면 null을 반환한다`() {
        assertThat(service.parseJudgement("판단할 수 없습니다")).isNull()
    }
}
