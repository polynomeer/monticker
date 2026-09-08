package com.monticker.worker.toss

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Toss 실시간 시세 WebSocket 커넥션 하나를 나타낸다(Spring 빈 아님) — ADR-031이 정한
 * "연결 A(미국)/연결 B(국내)" 배분은 이 클래스를 두 번 인스턴스화해 구현한다
 * (TossExecutionTickSubscriber 참고). KIS와 달리 인증은 메시지 본문이 아니라 handshake
 * 시 Authorization 헤더로 1회만 이뤄지고, 구독은 pipe-delimited가 아니라
 * `[{"type":"trade:kr","codes":[...]}]` 형태의 JSON 배열이다.
 */
class TossWebSocketClient(
    private val label: String,
    private val tokenIssuer: TossTokenIssuer,
    private val handlersByChannel: Map<String, TossRealtimeHandler>,
    private val wsUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    private val wsRef = AtomicReference<WebSocket?>()
    private val connected = AtomicBoolean(false)

    // (channel, code) 쌍 — 재연결 시 그대로 재구독한다.
    private val declarations = CopyOnWriteArraySet<Pair<String, String>>()

    val isConfigured: Boolean get() = tokenIssuer.isConfigured

    fun connect() {
        if (!isConfigured) {
            log.info("Toss platform key not configured — skipping WebSocket[{}] connection", label)
            return
        }
        if (connected.get()) return

        val token = tokenIssuer.getAccessToken() ?: return
        val listener = TossWebSocketListener(label, declarations, handlersByChannel, ::onDisconnected)

        try {
            val ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer $token")
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), listener)
                .get()
            wsRef.set(ws)
            connected.set(true)
            log.info("Toss WebSocket[{}] connected", label)
        } catch (e: Exception) {
            log.error("Toss WebSocket[{}] connect failed: {}", label, e.message)
        }
    }

    /** 종목 리스트를 배열 하나로 묶어 선언한다 — "초당 5회" 한도를 피하기 위함(ADR-031). */
    fun declare(channel: String, codes: List<String>) {
        if (!isConfigured || codes.isEmpty()) return
        codes.forEach { declarations.add(channel to it) }
        val ws = wsRef.get() ?: return
        if (!connected.get()) return

        ws.sendText(buildDeclareMessage(channel, codes), true)
        log.info("Toss WebSocket[{}] declared {} codes on {}", label, codes.size, channel)
    }

    /** 180초 무수신 disconnect를 피하기 위한 네이티브 WS ping — 60초 주기 권장(ADR-031). */
    fun sendKeepalivePing() {
        val ws = wsRef.get() ?: return
        if (!connected.get()) return
        runCatching { ws.sendPing(ByteBuffer.allocate(0)) }
            .onFailure { log.warn("Toss WebSocket[{}] ping failed: {}", label, it.message) }
    }

    fun disconnect() {
        wsRef.get()?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        connected.set(false)
    }

    private fun onDisconnected() {
        connected.set(false)
        wsRef.set(null)
        log.warn("Toss WebSocket[{}] disconnected", label)
    }
}

// ── Shared helpers ─────────────────────────────────────────────────────────────

private val declareMessageMapper = ObjectMapper()

private fun buildDeclareMessage(channel: String, codes: List<String>): String =
    declareMessageMapper.writeValueAsString(listOf(mapOf("type" to channel, "codes" to codes)))

// ── Listener ─────────────────────────────────────────────────────────────────

private class TossWebSocketListener(
    private val label: String,
    private val declarations: Set<Pair<String, String>>,
    private val handlersByChannel: Map<String, TossRealtimeHandler>,
    private val onDisconnected: () -> Unit,
) : WebSocket.Listener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val sb = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        log.info("Toss WebSocket[{}] open — re-declaring {} codes", label, declarations.size)
        declarations.groupBy({ it.first }, { it.second }).forEach { (channel, codes) ->
            webSocket.sendText(buildDeclareMessage(channel, codes), true)
        }
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        sb.append(data)
        if (last) {
            val raw = sb.toString()
            sb.clear()
            handleMessage(raw)
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        log.warn("Toss WebSocket[{}] closed: {} {}", label, statusCode, reason)
        onDisconnected()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        log.error("Toss WebSocket[{}] error: {}", label, error.message)
        onDisconnected()
    }

    private fun handleMessage(raw: String) {
        try {
            val root = mapper.readTree(raw)
            val type = root["type"]?.asText()
            if (type != "message") {
                // control 프레임(구독 ack, rate-limit-exceeded 등) — 별도 처리 없이 로그만 남긴다.
                log.debug("Toss WebSocket[{}] non-data message: {}", label, raw.take(200))
                return
            }
            val topic = root["topic"]?.asText() ?: return
            val channel = topic.substringBefore(":")
            val handler = handlersByChannel[channel] ?: return
            val data = root["data"] ?: return
            handler.handle(topic, data)
        } catch (e: Exception) {
            log.warn("Toss WebSocket[{}] message parse error: {}", label, e.message)
        }
    }
}
