package com.monticker.api.community.application

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.common.config.AnthropicConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private const val FAIL_CLOSED_MESSAGE = "지금은 댓글을 게시할 수 없습니다. 잠시 후 다시 시도해주세요."
private const val SOLICITATION_MESSAGE = "매수·매도를 권유하는 표현은 게시할 수 없습니다."

sealed class ModerationResult {
    object Allowed : ModerationResult()
    data class Blocked(val reason: String) : ModerationResult()
}

internal data class ModerationJudgement(val isRecommendation: Boolean, val reason: String)

/**
 * ADR-037 — 매수·매도 권유 필터. 키워드로 명백한 케이스를 무료·즉시 차단하고, 나머지는
 * AI(Claude)에게 판정을 맡긴다. 이 필터는 컴플라이언스 게이트이므로 AI 미설정/실패 시
 * fail-closed(게시 차단)로 동작한다 — StockSummaryService의 fail-open 폴백과 다르다.
 */
@Service
class CommentModerationService(
    private val anthropicClient: AnthropicClient,
    private val anthropicConfig: AnthropicConfig,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jsonPattern = Regex("\\{[\\s\\S]*}")

    private val solicitationKeywords = listOf(
        "사세요", "파세요", "매수하세요", "매도하세요", "지금 매수", "지금 매도", "지금 사", "지금 팔",
        "매수 추천", "매도 추천", "매수각", "매도각", "가즈아", "풀매수", "풀매도", "손절하세요", "익절하세요",
    )

    fun check(content: String): ModerationResult {
        if (solicitationKeywords.any { content.contains(it) }) {
            return ModerationResult.Blocked(SOLICITATION_MESSAGE)
        }

        if (!anthropicConfig.isConfigured) {
            log.warn("AI 모더레이션 미설정 — fail-closed로 댓글 차단")
            return ModerationResult.Blocked(FAIL_CLOSED_MESSAGE)
        }

        return try {
            val judgement = judge(content) ?: return ModerationResult.Blocked(FAIL_CLOSED_MESSAGE)
            if (judgement.isRecommendation) ModerationResult.Blocked(SOLICITATION_MESSAGE) else ModerationResult.Allowed
        } catch (e: Exception) {
            log.error("AI 모더레이션 호출 실패 — fail-closed로 댓글 차단: {}", e.message)
            ModerationResult.Blocked(FAIL_CLOSED_MESSAGE)
        }
    }

    private fun judge(content: String): ModerationJudgement? {
        val prompt = """
            다음 댓글이 특정 종목의 매수 또는 매도를 권유하는 내용인지 판단해줘.
            단순한 의견·감상·사실 전달은 권유가 아니다. "오를 것 같다", "실적이 좋다" 같은
            의견/전망은 권유가 아니지만, "지금 사라", "매수 추천" 같은 직접적인 행동 권유는 권유다.

            댓글: "$content"

            반드시 아래 JSON 형식으로만 답하고, 그 외 다른 텍스트는 절대 포함하지 마:
            {"isRecommendation": true 또는 false, "reason": "1문장으로 판단 근거"}
        """.trimIndent()

        val params = MessageCreateParams.builder()
            .model(Model.CLAUDE_HAIKU_4_5_20251001)
            .maxTokens(200L)
            .addUserMessage(prompt)
            .build()

        val response = anthropicClient.messages().create(params)
        val text = response.content().stream()
            .flatMap { it.text().stream() }
            .map { it.text() }
            .findFirst()
            .orElse(null) ?: return null

        return parseJudgement(text)
    }

    internal fun parseJudgement(text: String): ModerationJudgement? {
        val json = jsonPattern.find(text)?.value ?: return null
        return try {
            objectMapper.readValue(json, ModerationJudgement::class.java)
        } catch (e: Exception) {
            log.warn("AI 모더레이션 응답 파싱 실패: {}", e.message)
            null
        }
    }
}
