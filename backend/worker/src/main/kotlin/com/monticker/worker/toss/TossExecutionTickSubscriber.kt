package com.monticker.worker.toss

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * ADR-031 — 계정당 동시 연결 2개 한도를 그대로 활용해 커넥션을 상보적으로 나눈다:
 *   usConnection : trade:us — 미국 51종목 전체(현재 KIS/Toss 어느 쪽도 안 다루던 공백)
 *   krConnection : trade:kr — KisCoverageProvider가 커버하지 않는 국내 종목 중 최대 100개
 *
 * ingestion.source가 kis를 포함하지 않아도(즉 KIS 없이 toss만) 동작한다 —
 * TossCoverageProvider.krTargets는 그 경우 KIS 제외분 없이 국내 상위 100개가 된다.
 */
@Component
@ConditionalOnExpression("'\${ingestion.source:internal}'.contains('toss')")
class TossExecutionTickSubscriber(
    private val tokenIssuer: TossTokenIssuer,
    private val coverage: TossCoverageProvider,
    private val tickHandler: TossExecutionTickHandler,
    @Value("\${toss.ws-url:wss://openapi-ws.tossinvest.com/ws/v1}") private val wsUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val usConnection by lazy {
        TossWebSocketClient("US", tokenIssuer, mapOf(tickHandler.channelPrefix to tickHandler), wsUrl)
    }
    private val krConnection by lazy {
        TossWebSocketClient("KR", tokenIssuer, mapOf(tickHandler.channelPrefix to tickHandler), wsUrl)
    }

    @PostConstruct
    fun start() {
        if (!tokenIssuer.isConfigured) {
            log.warn("ingestion.source에 toss가 포함되지만 TOSS_PLATFORM_APP_KEY/SECRET이 설정되지 않았습니다 — Toss 실시간체결가 비활성")
            return
        }
        connectAndDeclare()
    }

    @Scheduled(fixedDelay = 60_000)
    fun keepAliveAndReconnect() {
        if (!tokenIssuer.isConfigured) return
        // 연결돼 있으면 ping으로 180초 idle 종료를 피하고, 끊겨 있으면 재연결한다.
        usConnection.sendKeepalivePing()
        krConnection.sendKeepalivePing()
        connectAndDeclare()
    }

    @PreDestroy
    fun stop() {
        usConnection.disconnect()
        krConnection.disconnect()
    }

    private fun connectAndDeclare() {
        try {
            usConnection.connect()
            usConnection.declare("trade:us", coverage.usTargets.map { it.symbol })

            krConnection.connect()
            krConnection.declare("trade:kr", coverage.krTargets.map { it.symbol })

            log.info(
                "Toss execution tick coverage — US:{} KR:{}",
                coverage.usTargets.size, coverage.krTargets.size,
            )
        } catch (e: Exception) {
            log.warn("Toss execution tick subscribe failed: {}", e.message)
        }
    }
}
