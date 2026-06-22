package com.monticker.api.common.config

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AnthropicConfig(
    @Value("\${anthropic.api-key:}") private val apiKey: String,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    @Bean
    fun anthropicClient(): AnthropicClient =
        if (apiKey.isNotBlank()) {
            AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        } else {
            AnthropicOkHttpClient.fromEnv()
        }
}
