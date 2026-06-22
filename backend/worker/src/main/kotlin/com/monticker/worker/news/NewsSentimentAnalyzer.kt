package com.monticker.worker.news

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class NewsSentimentAnalyzer(
    @Value("\${anthropic.api-key:}") private val apiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: AnthropicClient? = if (apiKey.isNotBlank()) {
        AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    } else {
        runCatching { AnthropicOkHttpClient.fromEnv() }.getOrNull()
    }

    /** Returns: POSITIVE, NEGATIVE, NEUTRAL, or null (fallback) */
    fun analyze(title: String, description: String?): String? {
        if (client == null) return null

        return try {
            val text = listOfNotNull(title, description?.take(200)).joinToString(". ")
            val params = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5_20251001)
                .maxTokens(16L)
                .addUserMessage(
                    "다음 주식 뉴스 제목의 감성을 POSITIVE, NEGATIVE, NEUTRAL 중 하나로만 답해줘. 설명 없이 단어만.\n\n$text"
                )
                .build()

            val response = client.messages().create(params)
            val raw = response.content().stream()
                .flatMap { it.text().stream() }
                .map { it.text().trim().uppercase() }
                .findFirst()
                .orElse(null)

            when {
                raw != null && raw.contains("POSITIVE") -> "POSITIVE"
                raw != null && raw.contains("NEGATIVE") -> "NEGATIVE"
                raw != null && raw.contains("NEUTRAL")  -> "NEUTRAL"
                else -> null
            }
        } catch (e: Exception) {
            log.debug("Sentiment analysis failed: {}", e.message)
            null
        }
    }
}
