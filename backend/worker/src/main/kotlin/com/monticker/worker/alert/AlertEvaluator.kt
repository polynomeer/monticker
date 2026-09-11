package com.monticker.worker.alert

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal

data class AlertRuleRow(
    val id: Long,
    val userId: Long,
    val stockId: Long,
    val ruleType: String,
    val conditionJson: String,
)

/**
 * 가격 알림 규칙 평가기 (ADR-044).
 *
 * 틱 1건당 하는 일: 인메모리 인덱스에서 그 종목의 룰을 꺼내(DB 조회 없음) 가격 룰은 이진 탐색으로,
 * 나머지는 60초 캐시된 지표로 판정하고, 발동한 룰을 AlertTriggerSink에 넘긴다. 외부 I/O는 없다.
 *
 * 이전: 틱마다 alert_rules SELECT + 룰 타입별로 candles_1d 집계 — L-03 기준선에서 워커 처리 상한
 * 600 tick/s의 주원인. 이제 DB 접근은 종목당 지표당 분당 1회로 상한이 걸린다.
 *
 * 룰 단위로 격리한다 — 한 룰의 예외가 같은 종목의 나머지 룰을 막지 않는다. 실패는 ruleType별 카운터
 * (alert_rule_eval_failed_total, 알람 AlertRuleEvalFailing).
 */
@Component
class AlertEvaluator(
    private val ruleIndex: AlertRuleIndex,
    private val indicators: IndicatorCache,
    private val sink: AlertTriggerSink,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    @EventListener
    @Async("alertDispatchExecutor")
    fun onTickProcessed(event: TickProcessedEvent) = processAlert(event.stockId, event.price)

    // AlertKafkaConsumer(role=alert)에서도 직접 호출한다
    fun processAlert(stockId: Long, price: BigDecimal) {
        val rules = try {
            ruleIndex.rulesFor(stockId)
        } catch (e: Exception) {
            meterRegistry.counter("alert_rule_eval_failed_total", "ruleType", "_fetch").increment()
            log.error("[AlertEvaluator] stockId={} 룰 조회 오류: {}", stockId, e.message)
            return
        }
        // 가격 룰: 이진 탐색으로 발동 구간만 잘라낸다. 의미론은 레벨 기반 그대로(쿨다운이 반복을 막는다).
        for (rule in rules.aboveTriggered(price)) safely(rule) { sink.triggered(rule, price) }
        for (rule in rules.belowTriggered(price)) safely(rule) { sink.triggered(rule, price) }
        // 지표 룰: 캐시된 지표로 판정
        for (rule in rules.others) safely(rule) { if (evaluateOther(rule, price)) sink.triggered(rule, price) }
    }

    private inline fun safely(rule: AlertRuleRow, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            meterRegistry.counter("alert_rule_eval_failed_total", "ruleType", rule.ruleType).increment()
            log.error("[AlertEvaluator] ruleId={} type={} 평가 오류: {}", rule.id, rule.ruleType, e.message)
        }
    }

    private fun evaluateOther(rule: AlertRuleRow, currentPrice: BigDecimal): Boolean {
        val condition: Map<String, Any> = objectMapper.readValue(rule.conditionJson, object : TypeReference<Map<String, Any>>() {})
        return when (rule.ruleType) {
            // 임계 파싱에 실패해 인덱스의 정렬 배열에 못 들어간 가격 룰 — 이전과 같이 조용히 skip
            "PRICE_ABOVE", "PRICE_BELOW" -> false
            "RSI_BELOW", "RSI_ABOVE" -> {
                val period    = (condition["period"] as? Number)?.toInt() ?: 14
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return false
                val rsi = indicators.rsi(rule.stockId, period) ?: return false
                if (rule.ruleType == "RSI_BELOW") rsi < threshold else rsi > threshold
            }
            "PRICE_BELOW_MA", "PRICE_ABOVE_MA" -> {
                val period = (condition["period"] as? Number)?.toInt() ?: 20
                val ma = indicators.movingAverage(rule.stockId, period) ?: return false
                if (rule.ruleType == "PRICE_BELOW_MA") currentPrice < BigDecimal.valueOf(ma) else currentPrice > BigDecimal.valueOf(ma)
            }
            "HOLDING_DROP" -> {
                val dropPct = (condition["dropPct"] as? Number)?.toDouble() ?: return false
                val avgBuyPrice = indicators.avgBuyPrice(rule.userId, rule.stockId) ?: return false
                if (avgBuyPrice <= BigDecimal.ZERO) return false
                (avgBuyPrice - currentPrice).toDouble() / avgBuyPrice.toDouble() * 100 >= dropPct
            }
            "VOLUME_SURGE" -> {
                val surgeRatio = (condition["surgeRatio"] as? Number)?.toDouble() ?: 2.0
                val period     = (condition["period"] as? Number)?.toInt() ?: 20
                val (todayVol, avgVol) = indicators.volumeStats(rule.stockId, period)
                avgVol > 0 && todayVol > avgVol * surgeRatio
            }
            else -> false
        }
    }
}
