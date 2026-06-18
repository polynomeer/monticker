package com.monticker.api.marketdata.infrastructure

import com.monticker.api.marketdata.domain.PriceTick
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

@Component
class PriceBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    fun broadcast(tick: PriceTick) {
        val message = mapOf(
            "type" to "PRICE_UPDATED",
            "stockId" to tick.stockId,
            "symbol" to tick.symbol,
            "price" to tick.price,
            "volume" to tick.volume,
            "timestamp" to tick.tradeTime.toString(),
        )
        messagingTemplate.convertAndSend("/topic/stocks/${tick.stockId}", message)
        messagingTemplate.convertAndSend("/topic/market", message)
    }
}
