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
            symbols.forEach { ws.subscribe("H0STASP0", it) }
        } catch (e: Exception) {
            log.warn("KIS subscribe failed: {}", e.message)
        }
    }

    // 20건 — KisWebSocketClient.MAX_REGISTRATIONS(41, KIS 공식 한도)를
    // KisExecutionTickSubscriber(체결가, 21건)와 정적으로 나눈 값이다(ADR-030).
    // 이전 LIMIT 100은 이 한도를 검증 없이 초과했었다 — 이 경로가 한 번도 실행된
    // 적이 없어(플랫폼 KIS 앱키 미설정) 지금까지 드러나지 않았을 뿐이다.
    private fun fetchKoreanSymbols(): List<String> =
        jdbc.queryForList(
            "SELECT symbol FROM stocks WHERE market IN ('KOSPI', 'KOSDAQ') AND is_active = true ORDER BY id LIMIT 20",
            String::class.java,
        )
}
