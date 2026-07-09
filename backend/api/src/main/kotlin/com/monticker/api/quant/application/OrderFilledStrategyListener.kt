package com.monticker.api.quant.application

import com.monticker.api.matching.events.OrderFilledEvent
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 주문 체결 이벤트를 수신해 quant 모듈의 후속 처리를 수행하는 리스너.
 *
 * @ApplicationModuleListener: Modulith 이벤트 스토어 기반으로 실행 — 재시도 보장.
 * Kafka 외부화(@Externalized)는 별도 스레드에서 처리되므로 이 리스너는
 * 인-프로세스 quant 처리만 담당한다.
 */
@Component
class OrderFilledStrategyListener {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun onOrderFilled(event: OrderFilledEvent) {
        log.info("[Quant] OrderFilledEvent: userId={} stockId={} side={} qty={} price={}",
            event.userId, event.stockId, event.side, event.quantity, event.fillPrice)
    }
}
