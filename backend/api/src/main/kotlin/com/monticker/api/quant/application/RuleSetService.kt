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
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Service
class RuleSetService(
    private val ruleSetRepository: RuleSetRepository,
    private val backtestResultRepository: QuantBacktestResultRepository,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    // ─── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional
    fun create(userId: Long, req: CreateRuleSetRequest): RuleSetResponse {
        val json        = objectMapper.writeValueAsString(req.ruleDefinition)
        val fingerprint = sha256(json)
        val entity = RuleSet(
            userId              = userId,
            name                = req.name,
            description         = req.description,
            ruleDefinition      = json,
            ruleSetFingerprint  = fingerprint,
        )
        return ruleSetRepository.save(entity).toResponse()
    }

    fun findByUser(userId: Long): List<RuleSetResponse> =
        ruleSetRepository.findAllByUserId(userId).map { it.toResponse() }

    fun findById(id: Long, userId: Long): RuleSetResponse =
        ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
            .toResponse()

    @Transactional
    fun update(id: Long, userId: Long, req: UpdateRuleSetRequest): RuleSetResponse {
        val entity = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
        req.name?.let { entity.name = it }
        req.description?.let { entity.description = it }
        req.ruleDefinition?.let {
            val json       = objectMapper.writeValueAsString(it)
            entity.ruleDefinition     = json
            entity.ruleSetFingerprint = sha256(json)
            entity.version           += 1
        }
        entity.updatedAt = Instant.now()
        return ruleSetRepository.save(entity).toResponse()
    }

    @Transactional
    fun delete(id: Long, userId: Long) {
        val entity = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
        ruleSetRepository.delete(entity)
    }

    // ─── Backtest ──────────────────────────────────────────────────────────────

    @Transactional
    fun runBacktest(id: Long, userId: Long, req: QuantBacktestRequest): QuantBacktestResponse {
        val ruleSet = ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }

        // 1. Load 1m candles and aggregate to daily OHLCV
        val candles = loadDailyCandles(req.stockId, req.startDate, req.endDate)
        require(candles.isNotEmpty()) { "No candle data found for stock ${req.stockId}" }

        // 2. Parse rule_definition JSON
        val ruleDef = parseRuleDefinition(ruleSet.ruleDefinition)

        // 3. Run engine
        val result = QuantBacktestEngine.run(
            candles        = candles,
            ruleDef        = ruleDef,
            initialCapital = req.initialCapital,
            fromDate       = req.startDate,
            toDate         = req.endDate,
        )

        val m = result.metrics

        // 4. Persist
        val entity = QuantBacktestResult(
            ruleSetId        = ruleSet.id,
            ruleSetVersion   = ruleSet.version,
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

        // Update ruleSet status
        ruleSet.status   = RuleSetStatus.BACKTESTED
        ruleSet.updatedAt = Instant.now()
        ruleSetRepository.save(ruleSet)

        return saved.toResponse()
    }

    fun listBacktestResults(id: Long, userId: Long): List<QuantBacktestResponse> {
        // Verify ownership
        ruleSetRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NoSuchElementException("RuleSet $id not found") }
        return backtestResultRepository.findAllByRuleSetId(id).map { it.toResponse() }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun loadDailyCandles(stockId: Long, from: LocalDate, to: LocalDate): List<DailyCandle> {
        return jdbc.query(
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
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRuleDefinition(json: String): RuleDefinition {
        val tree = objectMapper.readValue(json, Map::class.java) as Map<String, Any>

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

        fun parseSizing(raw: Map<*, *>): PositionSizing =
            PositionSizing(
                type  = raw["type"] as String,
                value = (raw["value"] as Number).toDouble(),
            )

        return RuleDefinition(
            entryRules     = parseGroup(tree["entryRules"] as Map<*, *>),
            exitRules      = parseGroup(tree["exitRules"] as Map<*, *>),
            positionSizing = parseSizing(tree["positionSizing"] as Map<*, *>),
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ─── Mappers ───────────────────────────────────────────────────────────────

    private fun RuleSet.toResponse() = RuleSetResponse(
        id          = id,
        userId      = userId,
        name        = name,
        description = description,
        version     = version,
        status      = status.name,
        ruleDefinition = ruleDefinition,
        fingerprint = ruleSetFingerprint,
        createdAt   = createdAt.toString(),
        updatedAt   = updatedAt.toString(),
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

// ─── Request / Response DTOs ────────────────────────────────────────────────

data class CreateRuleSetRequest(
    val name: String,
    val description: String? = null,
    val ruleDefinition: Any,
)

data class UpdateRuleSetRequest(
    val name: String? = null,
    val description: String? = null,
    val ruleDefinition: Any? = null,
)

data class QuantBacktestRequest(
    val stockId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val initialCapital: Double = 10_000_000.0,
)

data class RuleSetResponse(
    val id: Long,
    val userId: Long,
    val name: String,
    val description: String?,
    val version: Int,
    val status: String,
    val ruleDefinition: String,
    val fingerprint: String,
    val createdAt: String,
    val updatedAt: String,
)

data class QuantBacktestResponse(
    val id: Long,
    val ruleSetId: Long,
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
