package com.monticker.worker.alert

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-044 — 활성 알림 룰을 워커 메모리에 종목별로 상주시킨다.
 *
 * 이전에는 틱마다 `SELECT ... FROM alert_rules WHERE stock_id = ?`를 날렸다 — T2 기준 30,000 SELECT/s.
 * L-03 기준선(resilience-plan §5.5)에서 단일 파티션 워커 처리 상한이 600 tick/s로 나온 주원인이다.
 * 룰은 초당 수만 번 읽히고 초당 수 건 바뀌며 총량이 메모리에 들어간다 — 캐시하기에 이상적이다.
 *
 * 가격 룰(PRICE_ABOVE/BELOW)은 임계가 정렬 배열로 두고 이진 탐색한다. 종목당 룰이 수천 개여도 스캔하지 않는다.
 * **의미론은 바꾸지 않는다**: "임계를 넘어선 상태면 발동(쿨다운 10분)"인 레벨 기반 그대로다.
 *
 * 준비 전(기동 직후 로드 중)에는 이전과 같은 DB 조회로 폴백한다 — 기동 중 알림이 유실되면 안 된다.
 * 변경 전파: AlertService(api)가 커밋 후 Redis pub/sub `alert:rules:changed`에 stockId를 발행하고
 * AlertRulesChangedSubscriber가 그 종목만 다시 읽는다. pub/sub은 at-most-once라 5분 주기로
 * `updated_at > lastSync`인 룰의 종목을 보정 재로드한다.
 */
class AlertRuleIndex(
    private val jdbc: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 종목의 룰. 가격 룰은 임계로 정렬해 두고, 나머지는 리스트로 순회한다. */
    class StockRules(rules: List<AlertRuleRow>) {
        val priceAbove: List<Pair<Double, AlertRuleRow>>   // threshold 오름차순
        val priceBelow: List<Pair<Double, AlertRuleRow>>   // threshold 내림차순
        val others: List<AlertRuleRow>
        val size: Int = rules.size

        init {
            val above = mutableListOf<Pair<Double, AlertRuleRow>>()
            val below = mutableListOf<Pair<Double, AlertRuleRow>>()
            val rest = mutableListOf<AlertRuleRow>()
            for (r in rules) {
                val t = r.thresholdOrNull()
                when {
                    r.ruleType == "PRICE_ABOVE" && t != null -> above += t to r
                    r.ruleType == "PRICE_BELOW" && t != null -> below += t to r
                    else -> rest += r
                }
            }
            priceAbove = above.sortedBy { it.first }
            priceBelow = below.sortedByDescending { it.first }
            others = rest
        }

        /** price > threshold 인 PRICE_ABOVE 룰 — 정렬 배열의 접두 구간. */
        fun aboveTriggered(price: BigDecimal): List<AlertRuleRow> {
            val p = price.toDouble()
            var lo = 0; var hi = priceAbove.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (priceAbove[mid].first < p) lo = mid + 1 else hi = mid }
            return priceAbove.subList(0, lo).map { it.second }
        }

        /** price < threshold 인 PRICE_BELOW 룰 — 내림차순 배열의 접두 구간. */
        fun belowTriggered(price: BigDecimal): List<AlertRuleRow> {
            val p = price.toDouble()
            var lo = 0; var hi = priceBelow.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (priceBelow[mid].first > p) lo = mid + 1 else hi = mid }
            return priceBelow.subList(0, lo).map { it.second }
        }
    }

    private val byStock = ConcurrentHashMap<Long, StockRules>()
    private val ready = AtomicBoolean(false)
    @Volatile private var lastSync: Instant = Instant.EPOCH

    init {
        Gauge.builder("alert_rule_index_size", byStock) { m -> m.values.sumOf { it.size }.toDouble() }
            .description("메모리에 상주하는 활성 알림 룰 수").register(meterRegistry)
        Gauge.builder("alert_rule_index_ready", ready) { if (it.get()) 1.0 else 0.0 }.register(meterRegistry)
    }

    fun isReady(): Boolean = ready.get()

    /** 준비되면 인덱스, 아니면 이전과 같은 DB 조회(폴백). 결과 형태가 같아 평가 코드는 구분하지 않는다. */
    fun rulesFor(stockId: Long): StockRules =
        if (ready.get()) byStock[stockId] ?: EMPTY else StockRules(fetchRulesForStock(stockId))

    /** 기동 시 1회 — 전체 활성 룰 로드. 완료 전까지 rulesFor()는 DB 폴백이다. */
    fun loadAll() {
        val started = Instant.now()
        val all = jdbc.query(
            "SELECT id, user_id, stock_id, rule_type, condition_json FROM alert_rules WHERE is_active = true AND stock_id IS NOT NULL",
            ROW_MAPPER,
        )
        val grouped = all.groupBy { it.stockId }.mapValues { StockRules(it.value) }
        byStock.clear(); byStock.putAll(grouped)
        lastSync = started
        ready.set(true)
        log.info("[AlertRuleIndex] 로드 완료: 룰 {}건, 종목 {}개", all.size, grouped.size)
    }

    /** 한 종목의 룰만 다시 읽는다 — pub/sub 변경 알림과 보정 재로드가 쓴다. */
    fun reload(stockId: Long) {
        val rules = fetchRulesForStock(stockId)
        if (rules.isEmpty()) byStock.remove(stockId) else byStock[stockId] = StockRules(rules)
    }

    /**
     * pub/sub 유실 보정 — lastSync 이후 바뀐(비활성화 포함) 룰의 종목을 다시 읽는다.
     * 5분 주기. 룰 CRUD는 초당 수 건이라 이 쿼리는 가볍다.
     */
    fun syncDelta(): Int {
        if (!ready.get()) return 0
        val since = lastSync
        val now = Instant.now()
        val changed = jdbc.query(
            "SELECT DISTINCT stock_id FROM alert_rules WHERE updated_at > ? AND stock_id IS NOT NULL",
            { rs, _ -> rs.getLong("stock_id") },
            Timestamp.from(since),
        )
        changed.forEach { reload(it) }
        lastSync = now
        if (changed.isNotEmpty()) log.info("[AlertRuleIndex] 보정 재로드: 종목 {}개", changed.size)
        return changed.size
    }

    private fun fetchRulesForStock(stockId: Long): List<AlertRuleRow> =
        jdbc.query(
            "SELECT id, user_id, stock_id, rule_type, condition_json FROM alert_rules WHERE stock_id = ? AND is_active = true",
            ROW_MAPPER,
            stockId,
        )

    companion object {
        private val EMPTY = StockRules(emptyList())
        val ROW_MAPPER = org.springframework.jdbc.core.RowMapper { rs: java.sql.ResultSet, _: Int ->
            AlertRuleRow(
                id            = rs.getLong("id"),
                userId        = rs.getLong("user_id"),
                stockId       = rs.getLong("stock_id"),
                ruleType      = rs.getString("rule_type"),
                conditionJson = rs.getString("condition_json"),
            )
        }
        private val thresholdRegex = Regex(""""threshold"\s*:\s*(-?\d+(?:\.\d+)?)""")
        // condition_json은 {"threshold": 70000} 형태. 전체 JSON 파싱 없이 숫자만 뽑는다 — 로드 시 1회라 비용은 무관하고
        // Jackson 의존을 인덱스 자료구조에 끌어오지 않기 위해서다.
        private fun AlertRuleRow.thresholdOrNull(): Double? = thresholdRegex.find(conditionJson)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
