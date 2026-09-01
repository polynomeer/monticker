package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
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
) {

    // ─── CRUD ──────────────────────────────────────────────────────────────────

    fun create(userId: Long, req: CreateRuleSetRequest): RuleSetResponse {
        @Suppress("UNCHECKED_CAST")
        val defMap = objectMapper.convertValue(req.ruleDefinition, Map::class.java) as Map<String, Any>
        val fingerprint = sha256(objectMapper.writeValueAsString(defMap))
        val doc = RuleSetDocument(
            userId             = userId,
            name               = req.name,
            description        = req.description,
            ruleDefinition     = defMap,
            ruleSetFingerprint = fingerprint,
        )
        return ruleSetRepository.save(doc).toResponse()
    }

    fun findByUser(userId: Long): List<RuleSetResponse> =
        ruleSetRepository.findAllByUserId(userId).map { it.toResponse() }

    fun findById(id: String, userId: Long): RuleSetResponse =
        ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
            .toResponse()

    fun update(id: String, userId: Long, req: UpdateRuleSetRequest): RuleSetResponse {
        val doc = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
        req.name?.let { doc.rename(it) }
        req.description?.let { doc.updateDescription(it) }
        req.ruleDefinition?.let {
            @Suppress("UNCHECKED_CAST")
            val defMap = objectMapper.convertValue(it, Map::class.java) as Map<String, Any>
            doc.updateDefinition(defMap, sha256(objectMapper.writeValueAsString(defMap)), req.changeSummary)
        }
        return ruleSetRepository.save(doc).toResponse()
    }

    fun delete(id: String, userId: Long) {
        val doc = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
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

        val ruleDef = parseRuleDefinition(doc.ruleDefinition)

        val result = QuantBacktestEngine.run(
            candles        = candles,
            ruleDef        = ruleDef,
            initialCapital = req.initialCapital,
            fromDate       = req.startDate,
            toDate         = req.endDate,
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
        return backtestResultRepository.findAllByRuleSetId(id).map { it.toResponse() }
    }

    /**
     * analytics 모듈(PositionSizerService)이 Kelly 포지션 사이징 계산에 사용하는 조회 API.
     */
    fun getLatestBacktestResult(ruleSetId: String): QuantBacktestResponse? =
        backtestResultRepository.findAllByRuleSetId(ruleSetId)
            .maxByOrNull { it.createdAt }
            ?.toResponse()

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun loadDailyCandles(stockId: Long, from: LocalDate, to: LocalDate): List<DailyCandle> =
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

    @Suppress("UNCHECKED_CAST")
    private fun parseRuleDefinition(def: Map<String, Any>): RuleDefinition {
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
        return RuleDefinition(
            entryRules     = parseGroup(def["entryRules"] as Map<*, *>),
            exitRules      = parseGroup(def["exitRules"] as Map<*, *>),
            positionSizing = parseSizing(def["positionSizing"] as Map<*, *>),
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
    )
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class CreateRuleSetRequest(
    val name: String,
    val description: String? = null,
    val ruleDefinition: Any,
)

data class UpdateRuleSetRequest(
    val name: String? = null,
    val description: String? = null,
    val ruleDefinition: Any? = null,
    val changeSummary: String? = null,
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
)
