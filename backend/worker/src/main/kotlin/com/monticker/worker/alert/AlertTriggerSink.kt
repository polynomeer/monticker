package com.monticker.worker.alert

import java.math.BigDecimal

/**
 * 평가가 "발동했다"를 넘기는 출구. 평가 스레드는 여기서 끝나야 한다 — 외부 I/O는 하지 않는다.
 *  - KafkaTriggerSink (기본): notify.commands 토픽으로 발행. NotifyKafkaConsumer가 AlertDispatcher를 호출한다.
 *  - InlineTriggerSink (테스트·alert.dispatch-mode=inline): 같은 스레드에서 바로 AlertDispatcher.
 */
fun interface AlertTriggerSink {
    fun triggered(rule: AlertRuleRow, price: BigDecimal)
}

class InlineTriggerSink(private val dispatcher: AlertDispatcher) : AlertTriggerSink {
    override fun triggered(rule: AlertRuleRow, price: BigDecimal) = dispatcher.dispatch(rule, price)
}
