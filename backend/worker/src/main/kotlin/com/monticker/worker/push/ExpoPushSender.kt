package com.monticker.worker.push

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class PushMessage(
    val to: String,
    val title: String,
    val body: String,
    val data: Map<String, Any> = emptyMap(),
    val sound: String = "default",
    val priority: String = "high",
)

data class PushResult(
    val token: String,
    val status: String,   // "ok" | "error"
    val message: String?,
)

@Component
class ExpoPushSender(
    private val cbRegistry: CircuitBreakerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper()
    private val EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send"

    fun send(messages: List<PushMessage>): List<PushResult> {
        if (messages.isEmpty()) return emptyList()
        val cb = cbRegistry.circuitBreaker("expoPush")
        return try {
            cb.executeCallable { sendInternal(messages) }
        } catch (e: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            log.warn("[CircuitBreaker:expoPush] OPEN — Push 발송 건너뜀 ({}건)", messages.size)
            emptyList()
        }
    }

    private fun sendInternal(messages: List<PushMessage>): List<PushResult> {
        if (messages.isEmpty()) return emptyList()

        return try {
            val body = mapper.writeValueAsString(messages.map { msg ->
                mapOf(
                    "to"       to msg.to,
                    "title"    to msg.title,
                    "body"     to msg.body,
                    "data"     to msg.data,
                    "sound"    to msg.sound,
                    "priority" to msg.priority,
                )
            })

            val request = HttpRequest.newBuilder()
                .uri(URI.create(EXPO_PUSH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            parseResults(messages, response.body())
        } catch (e: Exception) {
            log.error("Expo push send failed: {}", e.message)
            messages.map { PushResult(it.to, "error", e.message) }
        }
    }

    private fun parseResults(messages: List<PushMessage>, responseBody: String): List<PushResult> {
        return try {
            val root = mapper.readTree(responseBody)
            val dataNode = root["data"] ?: return messages.map { PushResult(it.to, "error", "no data") }

            val items = if (dataNode.isArray) dataNode.toList() else listOf(dataNode)

            messages.zip(items).map { (msg, item) ->
                PushResult(
                    token   = msg.to,
                    status  = item["status"]?.asText() ?: "error",
                    message = item["message"]?.asText(),
                )
            }
        } catch (e: Exception) {
            messages.map { PushResult(it.to, "error", e.message) }
        }
    }
}
