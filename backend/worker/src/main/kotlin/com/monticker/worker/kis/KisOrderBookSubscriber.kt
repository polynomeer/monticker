package com.monticker.worker.kis

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 앱 시작 시 KIS WebSocket 연결 후 DB에 등록된 국내 주식 종목을 호가 채널에 구독한다.
 * 연결이 끊기면 매 60초마다 재연결을 시도한다.
 */
@Component
class KisOrderBookSubscriber(
    private val ws: KisWebSocketClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun start() {
        if (!ws.isConfigured) {
            log.info("KIS keys not set — real-time order book disabled (using mock)")
            return
        }
        connectAndSubscribe()
    }

    @Scheduled(fixedDelay = 60_000)
    fun reconnectIfNeeded() {
        if (!ws.isConfigured) return
        // KisWebSocketClient tracks connection state; connect() is idempotent when already connected
        connectAndSubscribe()
    }

    @PreDestroy
    fun stop() {
        ws.disconnect()
    }

    private fun connectAndSubscribe() {
        try {
            ws.connect()
            val symbols = fetchKoreanSymbols()
            log.info("Subscribing to {} symbols for real-time order book", symbols.size)
            symbols.forEach { ws.subscribe(it) }
        } catch (e: Exception) {
            log.warn("KIS subscribe failed: {}", e.message)
        }
    }

    private fun fetchKoreanSymbols(): List<String> =
        jdbc.queryForList(
            "SELECT symbol FROM stocks WHERE market IN ('KOSPI', 'KOSDAQ') AND is_active = true LIMIT 100",
            String::class.java,
        )
}
