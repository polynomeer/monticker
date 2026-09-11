package com.monticker.worker.alert

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import com.monticker.worker.push.ExpoPushSender
import com.monticker.worker.push.PushMessage
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class AlertRuleRow(
    val id: Long,
    val userId: Long,
    val stockId: Long,
    val ruleType: String,
    val conditionJson: String,
)

/**
 * 가격 알림 규칙 평가기.
 *
 * [Before] @Scheduled(fixedDelay=5000): 전체 alert_rules 폴링 → 각 종목 현재가 DB 재조회
 * [After]  @EventListener(TickProcessedEvent): 틱마다 해당 stockId 규칙만 평가,
 *          틱의 price를 그대로 사용하므로 DB 가격 재조회 불필요.
 *
 * 효과:
 *  - 5초 지연 → 실시간 (틱 단위)
 *  - 전체 룰 스캔 → stockId 필터 쿼리
 *  - DB price 재조회 제거 → 쿼리 수 감소
 *  - self-injection 해킹 제거
 */
@Component
class AlertEvaluator(
    private val jdbc: JdbcTemplate,
    private val pushSender: ExpoPushSender,
    private val esOps: ElasticsearchOperations,
    private val redis: StringRedisTemplate,
    private val mailSender: JavaMailSender,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    @EventListener
    @Async("alertDispatchExecutor")
    fun onTickProcessed(event: TickProcessedEvent) = processAlert(event.stockId, event.price)

    // AlertKafkaConsumer(role=alert)에서도 직접 호출한다
    fun processAlert(stockId: Long, price: java.math.BigDecimal) {
        val rules = try {
            fetchRulesForStock(stockId)
        } catch (e: Exception) {
            meterRegistry.counter("alert_rule_eval_failed_total", "ruleType", "_fetch").increment()
            log.error("[AlertEvaluator] stockId={} 룰 조회 오류: {}", stockId, e.message)
            return
        }
        // 룰 단위로 격리한다 — 이전에는 바깥 try/catch 하나라 한 룰의 예외가 같은 종목의 나머지 룰
        // 평가를 전부 건너뛰게 했고, 그 실패는 로그로만 남았다. VOLUME_SURGE가 무효 SQL로
        // "한 번도 발동한 적 없었던" 사고(ADR-044)가 정확히 이 구조에서 나왔다.
        // 실패는 ruleType별 카운터로 남긴다. 알람: AlertRuleEvalFailing (resilience-plan §E7 / P1-2).
        for (rule in rules) {
            try {
                evaluateRule(rule, price)
            } catch (e: Exception) {
                meterRegistry.counter("alert_rule_eval_failed_total", "ruleType", rule.ruleType).increment()
                log.error("[AlertEvaluator] ruleId={} type={} 평가 오류: {}", rule.id, rule.ruleType, e.message)
            }
        }
    }

    private fun fetchRulesForStock(stockId: Long): List<AlertRuleRow> =
        jdbc.query(
            "SELECT id, user_id, stock_id, rule_type, condition_json FROM alert_rules WHERE stock_id = ? AND is_active = true",
            { rs, _ ->
                AlertRuleRow(
                    id            = rs.getLong("id"),
                    userId        = rs.getLong("user_id"),
                    stockId       = rs.getLong("stock_id"),
                    ruleType      = rs.getString("rule_type"),
                    conditionJson = rs.getString("condition_json"),
                )
            },
            stockId,
        )

    private fun evaluateRule(rule: AlertRuleRow, currentPrice: BigDecimal) {
        val condition: Map<String, Any> = objectMapper.readValue(
            rule.conditionJson,
            object : TypeReference<Map<String, Any>>() {},
        )
        val triggered = when (rule.ruleType) {
            "PRICE_ABOVE" -> {
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return
                currentPrice > BigDecimal.valueOf(threshold)
            }
            "PRICE_BELOW" -> {
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return
                currentPrice < BigDecimal.valueOf(threshold)
            }
            "RSI_BELOW", "RSI_ABOVE" -> {
                val period    = (condition["period"] as? Number)?.toInt() ?: 14
                val threshold = (condition["threshold"] as? Number)?.toDouble() ?: return
                val rsi = fetchRsi(rule.stockId, period) ?: return
                if (rule.ruleType == "RSI_BELOW") rsi < threshold else rsi > threshold
            }
            "PRICE_BELOW_MA", "PRICE_ABOVE_MA" -> {
                val period = (condition["period"] as? Number)?.toInt() ?: 20
                val ma = jdbc.queryForObject(
                    """
                    SELECT AVG(close) FROM (
                        SELECT close FROM candles_1d WHERE stock_id = ?
                        ORDER BY candle_time DESC LIMIT ?
                    ) t
                    """,
                    Double::class.java, rule.stockId, period,
                ) ?: return
                if (rule.ruleType == "PRICE_BELOW_MA") currentPrice < BigDecimal.valueOf(ma) else currentPrice > BigDecimal.valueOf(ma)
            }
            "HOLDING_DROP" -> {
                val dropPct = (condition["dropPct"] as? Number)?.toDouble() ?: return
                // 이 종목을 실제로 보유 중인 사용자에게만 의미가 있다 — 포지션이 없으면
                // (전량 매도했거나 애초에 안 산 경우) 평가할 기준 자체가 없어 조용히 skip.
                val avgBuyPrice = jdbc.query(
                    "SELECT avg_buy_price FROM portfolio_positions WHERE user_id = ? AND stock_id = ? AND net_qty > 0",
                    { rs, _ -> rs.getBigDecimal("avg_buy_price") },
                    rule.userId, rule.stockId,
                ).firstOrNull() ?: return
                if (avgBuyPrice <= BigDecimal.ZERO) return
                val actualDropPct = (avgBuyPrice - currentPrice).toDouble() / avgBuyPrice.toDouble() * 100
                actualDropPct >= dropPct
            }
            "VOLUME_SURGE" -> {
                val surgeRatio = (condition["surgeRatio"] as? Number)?.toDouble() ?: 2.0
                val period     = (condition["period"] as? Number)?.toInt() ?: 20
                // 이전 쿼리는 비집계 컬럼(c.volume)과 집계(AVG(c2.volume))를 GROUP BY 없이
                // 섞은 무효 SQL이라 매번 예외를 던졌고 processAlert()의 바깥 try/catch가
                // 조용히 삼켜 이 규칙이 한 번도 발동한 적이 없었다. 두 값을 독립된 스칼라
                // 서브쿼리로 분리해 유효한 SQL로 고친다.
                // 참고: today_vol은 장 초반일수록 avg_vol(확정된 전체 거래일 평균)보다
                // 구조적으로 작게 나온다 — 시간대별 정규화는 하지 않았으므로 장 후반에
                // 갈수록 더 신뢰할 수 있는 값이 된다.
                val row = jdbc.queryForMap(
                    """
                    SELECT
                        (SELECT volume FROM candles_1d
                           WHERE stock_id = ? AND candle_time >= DATE_TRUNC('day', NOW() AT TIME ZONE 'Asia/Seoul')
                        ) AS today_vol,
                        (SELECT AVG(volume) FROM candles_1d
                           WHERE stock_id = ?
                             AND candle_time >= NOW() - INTERVAL '$period days'
                             AND candle_time < DATE_TRUNC('day', NOW() AT TIME ZONE 'Asia/Seoul')
                        ) AS avg_vol
                    """,
                    rule.stockId, rule.stockId,
                )
                val todayVol = (row["today_vol"] as? Number)?.toLong() ?: 0L
                val avgVol   = (row["avg_vol"] as? Number)?.toDouble() ?: 1.0
                avgVol > 0 && todayVol > avgVol * surgeRatio
            }
            else -> false
        }
        if (triggered) dispatchAlert(rule, currentPrice)
    }

    // candles_1d 종가로 RSI(period)를 계산한다. backend/api의 quant/IndicatorEngine.rsi()와
    // 알고리즘은 동일(Wilder smoothing)하지만, worker 모듈이 api 모듈에 대한 그레이들
    // 의존성이 없어(백테스팅 엔진을 워커까지 끌어오면 배포 단위가 불필요하게 커진다)
    // 순수 계산 로직만 별도로 옮겨왔다 — quant-engine 모듈에도 이미 동일 클래스가
    // 중복 존재하는 것과 같은 이유다. 참고: docs/decisions/ 에 공유 지표 모듈 추출은
    // 아직 없음 — RSI/MA 조건이 늘어나면 그때 재검토.
    private fun fetchRsi(stockId: Long, period: Int): Double? {
        val closes = jdbc.query(
            "SELECT close FROM candles_1d WHERE stock_id = ? ORDER BY candle_time DESC LIMIT ?",
            { rs, _ -> rs.getBigDecimal("close").toDouble() },
            stockId, period * 3,
        ).asReversed()
        if (closes.size <= period) return null

        val changes = closes.zipWithNext { a, b -> b - a }
        var avgGain = changes.subList(0, period).filter { it > 0 }.sum() / period
        var avgLoss = changes.subList(0, period).filter { it < 0 }.map { -it }.sum() / period
        for (i in period until changes.size) {
            val change = changes[i]
            avgGain = (avgGain * (period - 1) + (if (change > 0) change else 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + (if (change < 0) -change else 0.0)) / period
        }
        if (avgLoss == 0.0) return 100.0
        return 100.0 - 100.0 / (1 + avgGain / avgLoss)
    }

    private fun dispatchAlert(rule: AlertRuleRow, currentPrice: BigDecimal) {
        val cooldownKey = "alert:cooldown:${rule.id}"
        val acquired = redis.opsForValue().setIfAbsent(cooldownKey, "1", Duration.ofSeconds(600))
        if (acquired != true) return

        val message = buildMessage(rule, currentPrice)

        val historyId = jdbc.queryForObject(
            """
            INSERT INTO alert_histories (rule_id, stock_id, triggered_at, message, delivery_status)
            VALUES (?, ?, ?, ?, 'PENDING')
            RETURNING id
            """,
            Long::class.java,
            rule.id, rule.stockId,
            java.sql.Timestamp.from(Instant.now()),
            message,
        ) ?: return

        log.info("[AlertEvaluator] triggered: ruleId={} userId={} price={}", rule.id, rule.userId, currentPrice)

        val tokens = jdbc.queryForList(
            "SELECT token FROM device_tokens WHERE user_id = ? AND is_active = true",
            String::class.java,
            rule.userId,
        )
        if (tokens.isEmpty()) {
            sendEmailFallback(rule.userId, message)
            jdbc.update("UPDATE alert_histories SET delivery_status = 'EMAIL_FALLBACK' WHERE id = ?", historyId)
            return
        }

        val results = pushSender.send(tokens.map { token ->
            PushMessage(
                to    = token,
                title = "monticker 알림",
                body  = message,
                data  = mapOf("stockId" to rule.stockId, "ruleId" to rule.id),
            )
        })

        val status = if (results.all { it.status == "ok" }) "SENT" else "FAILED"
        jdbc.update("UPDATE alert_histories SET delivery_status = ? WHERE id = ?", status, historyId)
        log.info("[AlertEvaluator] push sent: userId={} status={}", rule.userId, status)

        indexToEs(historyId, rule, message, status, Instant.now())
    }

    private fun indexToEs(id: Long, rule: AlertRuleRow, message: String, status: String, triggeredAt: Instant) {
        try {
            esOps.save(AlertHistoryDocument(
                id             = id.toString(),
                ruleId         = rule.id,
                userId         = rule.userId,
                stockId        = rule.stockId.takeIf { it != 0L },
                ruleType       = rule.ruleType,
                message        = message,
                deliveryStatus = status,
                triggeredAt    = triggeredAt,
            ))
        } catch (e: Exception) {
            log.warn("[AlertEvaluator] ES indexing failed for historyId={}: {}", id, e.message)
        }
    }

    private fun sendEmailFallback(userId: Long, message: String) {
        try {
            val email = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ? AND deleted_at IS NULL",
                String::class.java, userId,
            ) ?: return
            val mail = SimpleMailMessage().apply {
                setTo(email)
                subject = "[monticker] 알림"
                text    = "$message\n\n설정한 알림 조건이 충족되었습니다."
            }
            mailSender.send(mail)
            log.info("[AlertEvaluator] email fallback sent: userId={}", userId)
        } catch (e: Exception) {
            log.warn("[AlertEvaluator] email fallback failed: userId={} {}", userId, e.message)
        }
    }

    private fun buildMessage(rule: AlertRuleRow, price: BigDecimal) = when (rule.ruleType) {
        "PRICE_ABOVE"    -> "가격이 ₩${price.toLong().formatKR()} 이상이 되었습니다"
        "PRICE_BELOW"    -> "가격이 ₩${price.toLong().formatKR()} 이하가 되었습니다"
        "VOLUME_SURGE"   -> "거래량이 평균 대비 급증했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "RSI_BELOW"      -> "RSI가 과매도 구간에 진입했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "RSI_ABOVE"      -> "RSI가 과매수 구간에 진입했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "PRICE_BELOW_MA" -> "가격이 이동평균선 아래로 이탈했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "PRICE_ABOVE_MA" -> "가격이 이동평균선 위로 돌파했습니다 (현재가 ₩${price.toLong().formatKR()})"
        "HOLDING_DROP"   -> "보유 종목이 평단가 대비 큰 폭으로 하락했습니다 (현재가 ₩${price.toLong().formatKR()})"
        else             -> "알림 조건 충족: ${rule.ruleType}"
    }

    private fun Long.formatKR() = "%,d".format(this)
}
