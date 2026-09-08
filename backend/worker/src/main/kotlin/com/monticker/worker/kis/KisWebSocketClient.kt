package com.monticker.worker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
class KisWebSocketClient(
    @Value("\${kis.app-key:}") private val appKey: String,
    @Value("\${kis.app-secret:}") private val appSecret: String,
    handlers: List<KisRealtimeHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = ObjectMapper()
    private val handlersByTrId = handlers.associateBy { it.trId }

    private val WS_URL = "ws://ops.koreainvestment.com:21000"
    private val wsRef = AtomicReference<WebSocket?>()
    private val connected = AtomicBoolean(false)
    private val approvalKey = AtomicReference<String?>()
    private val approvalKeyExpiry = AtomicReference(Instant.EPOCH)

    // (trId, symbol) 쌍 — 커넥션 하나를 여러 TR 타입이 공유하므로 심볼만으론 식별 불가.
    private val subscriptions = CopyOnWriteArraySet<Pair<String, String>>()

    val isConfigured: Boolean get() = appKey.isNotBlank() && appSecret.isNotBlank()

    companion object {
        // KIS 공식 저장소(koreainvestment/open-trading-api) 명시: "1개 appkey당 최대
        // 41건 등록 제한". 커넥션당인지 appkey당 전역인지는 실 서버 검증 전까지 불확실해
        // 다중 커넥션 풀링 대신 이 값 하나로 방어한다(ADR-030).
        const val MAX_REGISTRATIONS = 41
    }

    // ── Approval Key ─────────────────────────────────────────────────────────

    @Synchronized
    fun getApprovalKey(): String? {
        if (!isConfigured) return null
        val current = approvalKey.get()
        if (current != null && Instant.now().isBefore(approvalKeyExpiry.get())) return current
        return issueApprovalKey()
    }

    private fun issueApprovalKey(): String? {
        return try {
            val body = mapper.writeValueAsString(mapOf(
                "grant_type" to "client_credentials",
                "appkey"     to appKey,
                "secretkey"  to appSecret,
            ))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://openapi.koreainvestment.com:9443/oauth2/Approval"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("KIS approval key failed: status={}", res.statusCode())
                return null
            }
            val root = mapper.readTree(res.body())
            val key = root["approval_key"]?.asText() ?: return null
            approvalKey.set(key)
            // WebSocket approval key expires in 24h
            approvalKeyExpiry.set(Instant.now().plusSeconds(82800L))
            log.info("KIS WebSocket approval key issued")
            key
        } catch (e: Exception) {
            log.error("KIS approval key error: {}", e.message)
            null
        }
    }

    // ── Connection ───────────────────────────────────────────────────────────

    fun connect() {
        if (!isConfigured) {
            log.info("KIS keys not configured — skipping WebSocket connection")
            return
        }
        if (connected.get()) return  // 이미 연결됨

        val key = getApprovalKey() ?: return

        log.info("Connecting to KIS WebSocket...")
        val listener = KisWebSocketListener(key, subscriptions, handlersByTrId, ::onDisconnected)

        try {
            val ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(WS_URL), listener)
                .get()
            wsRef.set(ws)
            connected.set(true)
            log.info("KIS WebSocket connected")
        } catch (e: Exception) {
            log.error("KIS WebSocket connect failed: {}", e.message)
        }
    }

    fun subscribe(trId: String, symbol: String) {
        if (!isConfigured) return
        val pair = trId to symbol
        if (pair !in subscriptions && subscriptions.size >= MAX_REGISTRATIONS) {
            log.warn("KIS WebSocket 등록 한도({}) 초과 — {}:{} 구독 건너뜀", MAX_REGISTRATIONS, trId, symbol)
            return
        }
        subscriptions.add(pair)
        val ws = wsRef.get() ?: return
        if (!connected.get()) return

        val key = approvalKey.get() ?: return
        ws.sendText(buildSubscribeMessage(key, trId, symbol), true)
        log.debug("Subscribed to {} for {}", trId, symbol)
    }

    fun disconnect() {
        wsRef.get()?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        connected.set(false)
    }

    private fun onDisconnected() {
        connected.set(false)
        wsRef.set(null)
        log.warn("KIS WebSocket disconnected")
    }
}

// ── Shared helpers ─────────────────────────────────────────────────────────────

private val subscribeMessageMapper = ObjectMapper()

private fun buildSubscribeMessage(approvalKey: String, trId: String, symbol: String): String =
    subscribeMessageMapper.writeValueAsString(mapOf(
        "header" to mapOf(
            "approval_key" to approvalKey,
            "custtype"     to "P",
            "tr_type"      to "1",
            "content-type" to "utf-8",
        ),
        "body" to mapOf("input" to mapOf(
            "tr_id"  to trId,
            "tr_key" to symbol,
        )),
    ))

// ── Listener ─────────────────────────────────────────────────────────────────

private class KisWebSocketListener(
    private val approvalKey: String,
    private val subscriptions: Set<Pair<String, String>>,
    private val handlersByTrId: Map<String, KisRealtimeHandler>,
    private val onDisconnected: () -> Unit,
) : WebSocket.Listener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val sb = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        log.info("KIS WebSocket open — re-subscribing {} registrations", subscriptions.size)
        // Re-subscribe after reconnect
        subscriptions.forEach { (trId, symbol) ->
            webSocket.sendText(buildSubscribeMessage(approvalKey, trId, symbol), true)
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
        log.warn("KIS WebSocket closed: {} {}", statusCode, reason)
        onDisconnected()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        log.error("KIS WebSocket error: {}", error.message)
        onDisconnected()
    }

    private fun handleMessage(raw: String) {
        // KIS sends pipe-delimited data for market data (starts with non-JSON)
        // Control messages are JSON (ping/pong, subscribe ack)
        if (raw.startsWith("{")) {
            log.debug("KIS control message: {}", raw.take(100))
            return
        }
        // Format: header|trId|dataCount|body — body fields are pipe-separated per TR type.
        val parts = raw.split("|")
        if (parts.size < 4) return

        val trId = parts[1]
        val handler = handlersByTrId[trId] ?: return

        try {
            handler.handle(parts)
        } catch (e: Exception) {
            log.warn("{} parse error: {}", trId, e.message)
        }
    }
}
