package com.monticker.api.watchrule.application

import com.monticker.api.quant.events.QuantSignalEmittedEvent
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener

/**
 * ADR-077 — 퀀트랩 포워드 테스트 신호로 "전략 신호" 규칙을 발동한다.
 *
 * 신호 트랜잭션이 커밋된 뒤(AFTER_COMMIT) 별도 스레드에서 실행된다. Modulith 이벤트 발행 기록이 트랜잭션 리스너를
 * 추적하므로 실패하면 미완료로 남아 재전달된다. 재전달돼도 (규칙, 신호) 유니크와 멱등 키 `WR:{ruleId}:Q{signalId}`가
 * 중복 체결을 막는다.
 *
 * @ApplicationModuleListener를 쓰지 않는 이유: 그건 REQUIRES_NEW 트랜잭션을 연다. 그 안에서 주문이 거부되면(사가 예외)
 * 트랜잭션이 rollback-only가 되어 REJECTED 기록 자체가 커밋되지 못하고, 리스너가 실패해 같은 거부를 무한 재전달한다.
 * 실행기는 Kafka 경로(WatchRuleConsumer)와 똑같이 트랜잭션 밖에서 돌아야 한다 — 주문마다 자기 트랜잭션.
 */
@Component
class WatchRuleQuantSignalListener(private val executor: WatchRuleExecutor) {

    @Async
    @TransactionalEventListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: QuantSignalEmittedEvent) {
        executor.onQuantSignal(event)
    }
}
