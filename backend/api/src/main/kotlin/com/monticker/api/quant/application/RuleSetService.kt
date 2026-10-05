package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.monticker.api.quant.domain.*
import com.monticker.api.quant.infrastructure.QuantBacktestResultRepository
import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

@Service
class RuleSetService(
    private val ruleSetRepository: RuleSetRepository,
    private val backtestResultRepository: QuantBacktestResultRepository,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val performanceQuery: StrategyPerformanceQuery,
) {

    // ─── CRUD ──────────────────────────────────────────────────────────────────

    fun create(userId: Long, req: CreateRuleSetRequest): RuleSetResponse {
        @Suppress("UNCHECKED_CAST")
        val defMap = objectMapper.convertValue(req.ruleDefinition, Map::class.java) as Map<String, Any>
        validateDefinition(defMap)
        val fingerprint = sha256(objectMapper.writeValueAsString(defMap))
        val doc = RuleSetDocument(
            userId             = userId,
            name               = req.name,
            description        = req.description,
            ruleDefinition     = defMap,
            ruleSetFingerprint = fingerprint,
            universeJson       = req.universeJson?.let { toStringAnyMap(it) } ?: emptyMap(),
        )
        return ruleSetRepository.save(doc).toResponse()
    }

    /** ADR-078 — 목록에는 카드용 성과 요약(최신 백테스트·포워드)을 같이 싣는다. */
    fun findByUser(userId: Long): List<RuleSetResponse> {
        val docs = ruleSetRepository.findAllByUserId(userId)
        val performance = performanceQuery.summarize(docs.mapNotNull { it.id })
        return docs.map { it.toResponse().copy(performance = performance[it.id]) }
    }

    fun findById(id: String, userId: Long): RuleSetResponse =
        ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
            .toResponse()

    fun update(id: String, userId: Long, req: UpdateRuleSetRequest): RuleSetResponse {
        val doc = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
        // ADR-024: 포워드 테스트 운용 중에는 이름/설명 변경도 막는다 — updateDefinition()의
        // 자체 가드는 ruleDefinition 필드가 실제로 바뀔 때만 걸리므로 이걸로는 부족하다.
        require(doc.status != RuleSetStatus.RUNNING.name) {
            "포워드 테스트 운용 중에는 룰셋을 수정할 수 없습니다. 먼저 중지해주세요."
        }
        req.name?.let { doc.rename(it) }
        req.description?.let { doc.updateDescription(it) }
        req.ruleDefinition?.let {
            val defMap = toStringAnyMap(it)
            validateDefinition(defMap)
            doc.updateDefinition(defMap, sha256(objectMapper.writeValueAsString(defMap)), req.changeSummary)
        }
        req.universeJson?.let { doc.updateUniverse(toStringAnyMap(it)) }
        return ruleSetRepository.save(doc).toResponse()
    }

    fun delete(id: String, userId: Long) {
        val doc = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }

        // ADR-035 — 마켓에 공유돼 구독자가 있는 룰셋을 그냥 지우면, 신호의 원본이 사라져
        // 구독자가 아무 알림 없이 신호를 못 받게 된다. 마켓에서 내리는 흐름(환불·통지 정책)은
        // 이번 범위 밖이라 지금은 구독자가 있으면 삭제 자체를 막는 것으로만 방어한다.
        val subscriberCount = jdbc.queryForObject(
            "SELECT COALESCE(SUM(subscribe_count), 0) FROM strategy_market WHERE ruleset_id = ?",
            Long::class.java, id,
        ) ?: 0L
        require(subscriberCount == 0L) { "구독자가 있는 전략은 삭제할 수 없습니다." }

        ruleSetRepository.delete(doc)
    }

    fun getVersionHistory(id: String, userId: Long): List<RuleVersionEntry> {
        val doc = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
        return doc.versions.sortedByDescending { it.version }
    }

    // ─── Backtest ──────────────────────────────────────────────────────────────

    @Transactional
    fun runBacktest(id: String, userId: Long, req: QuantBacktestRequest): QuantBacktestResponse {
        val doc = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }

        val candles = loadDailyCandles(req.stockId, req.startDate, req.endDate)
        require(candles.isNotEmpty()) { "No candle data found for stock ${req.stockId}" }

        require(verifyFingerprint(doc)) {
            "룰셋 무결성 검증 실패: ruleSetId=$id — ruleDefinition이 변조됐거나 fingerprint가 누락됐습니다"
        }

        val ruleDef = parseRuleDefinition(doc.ruleDefinition)

        val result = QuantBacktestEngine.run(
            candles        = candles,
            ruleDef        = ruleDef,
            initialCapital = req.initialCapital,
            fromDate       = req.startDate,
            toDate         = req.endDate,
            aux            = loadAuxData(req.stockId, req.startDate, req.endDate, ruleDef),
        )

        val m = result.metrics
        val entity = QuantBacktestResult(
            ruleSetId        = doc.id!!,
            ruleSetVersion   = doc.version,
            stockId          = req.stockId,
            startDate        = req.startDate,
            endDate          = req.endDate,
            initialCapital   = BigDecimal.valueOf(result.initialCapital),
            finalCapital     = BigDecimal.valueOf(result.finalCapital),
            totalReturn      = BigDecimal.valueOf(m.totalReturn),
            annualReturn     = BigDecimal.valueOf(m.annualReturn),
            mdd              = BigDecimal.valueOf(m.mdd),
            winRate          = BigDecimal.valueOf(m.winRate),
            profitFactor     = BigDecimal.valueOf(m.profitFactor),
            tradeCount       = m.tradeCount,
            avgHoldingDays   = BigDecimal.valueOf(m.avgHoldingDays),
            benchmarkReturn  = BigDecimal.valueOf(m.benchmarkReturn),
            excessReturn     = BigDecimal.valueOf(m.excessReturn),
            commissionRate   = BigDecimal("0.015"),
            slippageRate     = BigDecimal("0.1"),
            reliabilityScore = m.reliabilityScore,
            reliabilityNotes = objectMapper.writeValueAsString(m.reliabilityNotes),
            tradesJson       = objectMapper.writeValueAsString(result.trades),
            equityCurveJson  = objectMapper.writeValueAsString(result.equityCurve),
        )
        val saved = backtestResultRepository.save(entity)

        doc.markBacktested()
        ruleSetRepository.save(doc)

        return saved.toResponse()
    }

    fun listBacktestResults(id: String, userId: Long): List<QuantBacktestResponse> {
        ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
        return backtestResultRepository.findAllByRuleSetIdOrderByCreatedAtDescIdDesc(id).map { it.toResponse() }
    }

    /**
     * analytics 모듈(PositionSizerService)이 Kelly 포지션 사이징 계산에 사용하는 조회 API.
     */
    fun getLatestBacktestResult(ruleSetId: String): QuantBacktestResponse? =
        backtestResultRepository.findAllByRuleSetIdOrderByCreatedAtDescIdDesc(ruleSetId)
            .firstOrNull()
            ?.toResponse()

    // ─── Helpers ───────────────────────────────────────────────────────────────

    // internal — ForwardTestService도 동일한 일봉 조회/룰 파싱 로직을 재사용한다.
    internal fun loadDailyCandles(stockId: Long, from: LocalDate, to: LocalDate): List<DailyCandle> =
        jdbc.query(
            """
            SELECT
                DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d,
                (ARRAY_AGG(open  ORDER BY candle_time ASC))[1]  AS open,
                MAX(high)                                        AS high,
                MIN(low)                                         AS low,
                (ARRAY_AGG(close ORDER BY candle_time DESC))[1] AS close,
                SUM(volume)                                      AS volume
            FROM candles_1m
            WHERE stock_id = ?
              AND DATE(candle_time AT TIME ZONE 'Asia/Seoul') BETWEEN ? AND ?
            GROUP BY d
            ORDER BY d
            """.trimIndent(),
            { rs, _ ->
                DailyCandle(
                    date   = rs.getDate("d").toLocalDate(),
                    open   = rs.getBigDecimal("open"),
                    high   = rs.getBigDecimal("high"),
                    low    = rs.getBigDecimal("low"),
                    close  = rs.getBigDecimal("close"),
                    volume = rs.getLong("volume"),
                )
            },
            stockId, from, to,
        )

    /**
     * ADR-079 — 룰이 쓰는 보조 데이터만 읽는다. 키는 이용 가능일(장 마감 후 정보는 다음 날)이라
     * 하루 앞서서부터 읽는다.
     */
    internal fun loadAuxData(stockId: Long, from: LocalDate, to: LocalDate, ruleDef: RuleDefinition): QuantAuxData {
        val needsNews = AuxIndicators.uses(ruleDef, AuxIndicators.NEWS_SENTIMENT)
        val needsDisclosure = AuxIndicators.uses(ruleDef, AuxIndicators.DISCLOSURE)
        if (!needsNews && !needsDisclosure) return QuantAuxData.EMPTY

        val kst = java.time.ZoneId.of("Asia/Seoul")
        val fromTs = java.sql.Timestamp.from(from.minusDays(1).atStartOfDay(kst).toInstant())
        val toTs = java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(kst).toInstant())

        val sentiment = if (!needsNews) emptyMap() else jdbc.query(
            """SELECT published_at, sentiment FROM news_articles
               WHERE stock_id = ? AND sentiment IS NOT NULL AND published_at >= ? AND published_at < ?""",
            { rs, _ ->
                val day = AuxIndicators.availableDate(rs.getTimestamp("published_at").toInstant())
                day to when (rs.getString("sentiment")) {
                    "POSITIVE" -> SentimentCount(positive = 1)
                    "NEGATIVE" -> SentimentCount(negative = 1)
                    else       -> SentimentCount(neutral = 1)
                }
            },
            stockId, fromTs, toTs,
        ).groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.reduce(SentimentCount::plus) }

        val disclosures = if (!needsDisclosure) emptyMap() else jdbc.query(
            """SELECT event_time, metadata_json->>'reportName' AS report_name FROM stock_events
               WHERE stock_id = ? AND event_type = 'DISCLOSURE_PUBLISHED' AND event_time >= ? AND event_time < ?""",
            { rs, _ ->
                AuxIndicators.availableDate(rs.getTimestamp("event_time").toInstant()) to
                    AuxIndicators.classifyDisclosure(rs.getString("report_name") ?: "")
            },
            stockId, fromTs, toTs,
        ).groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten().toSet() }

        return QuantAuxData(sentiment, disclosures)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseRuleDefinition(def: Map<String, Any>): RuleDefinition {
        fun parseCondition(raw: Map<*, *>): RuleCondition {
            val params = (raw["params"] as? Map<*, *>)
                ?.entries?.associate { (k, v) -> k.toString() to (v as Any) }
                ?: emptyMap()
            return RuleCondition(
                indicator  = raw["indicator"] as String,
                comparator = raw["comparator"] as String,
                params     = params,
                value      = raw["value"],
            )
        }
        fun parseGroup(raw: Map<*, *>): RuleGroup {
            val conditions = (raw["conditions"] as List<*>)
                .filterIsInstance<Map<*, *>>()
                .map { parseCondition(it) }
            return RuleGroup(operator = raw["operator"] as String, conditions = conditions)
        }
        fun parseSizing(raw: Map<*, *>) = PositionSizing(
            type  = raw["type"] as String,
            value = (raw["value"] as Number).toDouble(),
        )
        val hard = def["hardExits"] as? Map<*, *>
        return RuleDefinition(
            entryRules     = parseGroup(def["entryRules"] as Map<*, *>),
            exitRules      = parseGroup(def["exitRules"] as Map<*, *>),
            positionSizing = parseSizing(def["positionSizing"] as Map<*, *>),
            hardExits      = HardExits(
                maxHoldDays     = (hard?.get("maxHoldDays") as? Number)?.toInt(),
                trailingStopPct = (hard?.get("trailingStopPct") as? Number)?.toDouble(),
            ),
        )
    }

    /**
     * 저장 전 입력 검증. 엔진은 모르는 값을 만나면 조용히 false로 평가하므로, 사용자가 잘못 넣은
     * 값이 "조건이 한 번도 안 맞는 전략"으로 굳기 전에 400으로 돌려보낸다. 보조 데이터 지표(ADR-079)부터
     * 적용한다 — 기존 지표의 느슨한 동작은 그대로 둔다.
     */
    internal fun validateDefinition(def: Map<String, Any>) {
        (def["hardExits"] as? Map<*, *>)?.let { h ->
            (h["maxHoldDays"] as? Number)?.let {
                require(it.toDouble() % 1.0 == 0.0 && it.toInt() in 1..500) { "최대 보유 기간은 1~500 거래일 정수여야 합니다." }
            }
            (h["trailingStopPct"] as? Number)?.let {
                require(it.toDouble() > 0.0 && it.toDouble() <= 50.0) { "트레일링 스탑은 0% 초과 50% 이하여야 합니다." }
            }
            require(h.keys.all { it == "maxHoldDays" || it == "trailingStopPct" }) { "알 수 없는 강제 청산 항목입니다: ${h.keys}" }
        }
        val conditions = listOf("entryRules", "exitRules").flatMap { key ->
            ((def[key] as? Map<*, *>)?.get("conditions") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        }
        for (c in conditions) {
            val indicator = (c["indicator"] as? String)?.uppercase() ?: continue
            if (indicator != AuxIndicators.NEWS_SENTIMENT && indicator != AuxIndicators.DISCLOSURE) continue
            val period = ((c["params"] as? Map<*, *>)?.get("period") as? Number)?.toDouble() ?: 5.0
            require(period >= 1 && period <= 60 && period % 1.0 == 0.0) { "$indicator 기간은 1~60 거래일 정수여야 합니다." }
            val comparator = (c["comparator"] as? String)?.uppercase()
            if (indicator == AuxIndicators.DISCLOSURE) {
                require(comparator in AuxIndicators.DISCLOSURE_CATEGORIES) { "알 수 없는 공시 유형입니다: $comparator" }
            } else {
                require(comparator in setOf("GT", "GTE", "LT", "LTE")) { "뉴스 감성은 크다/작다 비교만 쓸 수 있습니다." }
                val v = (c["value"] as? Number)?.toDouble()
                require(v != null && v >= -1.0 && v <= 1.0) { "뉴스 감성 기준값은 -1~1 사이여야 합니다." }
            }
        }
    }

    internal fun verifyFingerprint(doc: RuleSetDocument): Boolean =
        sha256(objectMapper.writeValueAsString(doc.ruleDefinition)) == doc.ruleSetFingerprint

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toStringAnyMap(value: Any): Map<String, Any> =
        objectMapper.convertValue(value, Map::class.java) as Map<String, Any>

    // ─── Mappers ───────────────────────────────────────────────────────────────

    private fun RuleSetDocument.toResponse() = RuleSetResponse(
        id             = id!!,
        userId         = userId,
        name           = name,
        description    = description,
        version        = version,
        status         = status,
        ruleDefinition = objectMapper.writeValueAsString(ruleDefinition),
        universeJson   = objectMapper.writeValueAsString(universeJson),
        fingerprint    = ruleSetFingerprint,
        versionCount   = versions.size,
        createdAt      = createdAt.toString(),
        updatedAt      = updatedAt.toString(),
    )

    private fun QuantBacktestResult.toResponse() = QuantBacktestResponse(
        id               = id,
        ruleSetId        = ruleSetId,
        ruleSetVersion   = ruleSetVersion,
        stockId          = stockId,
        startDate        = startDate,
        endDate          = endDate,
        initialCapital   = initialCapital.toDouble(),
        finalCapital     = finalCapital.toDouble(),
        totalReturn      = totalReturn?.toDouble(),
        annualReturn     = annualReturn?.toDouble(),
        mdd              = mdd?.toDouble(),
        winRate          = winRate?.toDouble(),
        profitFactor     = profitFactor?.toDouble(),
        tradeCount       = tradeCount,
        avgHoldingDays   = avgHoldingDays?.toDouble(),
        benchmarkReturn  = benchmarkReturn?.toDouble(),
        excessReturn     = excessReturn?.toDouble(),
        reliabilityScore = reliabilityScore,
        createdAt        = createdAt.toString(),
        trades           = tradesJson?.let { objectMapper.readValue<List<QuantTradeRecord>>(it) } ?: emptyList(),
        equityCurve      = equityCurveJson?.let { objectMapper.readValue<List<QuantEquityPoint>>(it) } ?: emptyList(),
    )
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class CreateRuleSetRequest(
    val name: String,
    val description: String? = null,
    val ruleDefinition: Any,
    val universeJson: Any? = null,
)

data class UpdateRuleSetRequest(
    val name: String? = null,
    val description: String? = null,
    val ruleDefinition: Any? = null,
    val changeSummary: String? = null,
    val universeJson: Any? = null,
)

data class QuantBacktestRequest(
    val stockId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val initialCapital: Double = 10_000_000.0,
)

data class RuleSetResponse(
    val id: String,
    val userId: Long,
    val name: String,
    val description: String?,
    val version: Int,
    val status: String,
    val ruleDefinition: String,
    val universeJson: String,
    val fingerprint: String,
    val versionCount: Int,
    val createdAt: String,
    val updatedAt: String,
    /** 목록 조회에서만 채운다(ADR-078). 단건 조회는 null */
    val performance: StrategyPerformance? = null,
)

data class QuantBacktestResponse(
    val id: Long,
    val ruleSetId: String,
    val ruleSetVersion: Int,
    val stockId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val initialCapital: Double,
    val finalCapital: Double,
    val totalReturn: Double?,
    val annualReturn: Double?,
    val mdd: Double?,
    val winRate: Double?,
    val profitFactor: Double?,
    val tradeCount: Int?,
    val avgHoldingDays: Double?,
    val benchmarkReturn: Double?,
    val excessReturn: Double?,
    val reliabilityScore: String?,
    val createdAt: String,
    val trades: List<QuantTradeRecord>,
    val equityCurve: List<QuantEquityPoint>,
)
