package com.monticker.worker.kis

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * ingestion.source=kis일 때 KisCoverageProvider가 계산한 종목을 H0STCNT0(실시간체결가)
 * 채널에 구독한다. KisOrderBookSubscriber와 구조는 같지만 별도 컴포넌트로 둔 이유는
 * ingestion.source=kis가 아닌 한(기본값 internal/kafka) 아예 등록되지 않아야 하기
 * 때문 — 호가 구독은 이 조건과 무관하게 kis.app-key만 있으면 계속 동작한다.
 */
@Component
@ConditionalOnProperty(name = ["ingestion.source"], havingValue = "kis")
class KisExecutionTickSubscriber(
    private val ws: KisWebSocketClient,
    private val coverage: KisCoverageProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun start() {
        if (!ws.isConfigured) {
            log.warn("ingestion.source=kis이지만 KIS_APP_KEY/KIS_APP_SECRET이 설정되지 않았습니다 — 실시간체결가 비활성")
            return
        }
        connectAndSubscribe()
    }

    @Scheduled(fixedDelay = 60_000)
    fun reconnectIfNeeded() {
        if (!ws.isConfigured) return
        connectAndSubscribe()
    }

    @PreDestroy
    fun stop() {
        ws.disconnect()
    }

    private fun connectAndSubscribe() {
        try {
            ws.connect()
            log.info("Subscribing to {} symbols for real-time execution ticks (H0STCNT0)", coverage.targets.size)
            coverage.targets.forEach { ws.subscribe("H0STCNT0", it.symbol) }
        } catch (e: Exception) {
            log.warn("KIS execution tick subscribe failed: {}", e.message)
        }
    }
}
