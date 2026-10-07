package com.monticker.api.quant.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.monticker.api.quant.domain.QuantBacktestResult
import com.monticker.api.quant.domain.QuantEquityPoint
import com.monticker.api.quant.domain.QuantForwardTest
import com.monticker.api.quant.infrastructure.QuantBacktestResultRepository
import com.monticker.api.quant.infrastructure.QuantForwardTestRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 카드에 그릴 최신 백테스트 요약 — 전체 거래 내역·일별 곡선 대신 지표와 다운샘플 곡선만. */
data class BacktestSummary(
    val id: Long,
    val stockId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalReturn: Double?,
    val annualReturn: Double?,
    val mdd: Double?,
    val sharpe: Double?,
    val tradeCount: Int?,
    val reliabilityScore: String?,
    /** 일별 운용 자산을 최대 [StrategyPerformanceQuery.CURVE_POINTS]개로 줄인 값(첫·마지막 점 포함) */
    val curve: List<Double>,
)

data class ForwardSummary(
    val status: String,
    val startedAt: String,
    val stoppedAt: String?,
    val matchRate: Double?,
    val matchedSignals: Int?,
    val comparedSignals: Int?,
)

data class StrategyPerformance(
    val backtest: BacktestSummary?,
    val forward: ForwardSummary?,
)

/**
 * ADR-078 — 목록 화면(퀀트랩 카드·전략 마켓 카드)용 성과 요약. 룰셋 N개에 대해 쿼리 2번
 * (최신 백테스트 DISTINCT ON, 포워드 테스트 IN)으로 끝낸다. 예전에는 카드마다 백테스트 전체
 * 결과(거래 내역·일별 곡선 포함)를 따로 받아 왔다.
 */
@Component
class StrategyPerformanceQuery(
    private val backtestResultRepository: QuantBacktestResultRepository,
    private val forwardTestRepository: QuantForwardTestRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CURVE_POINTS = 48

        /** 첫 점과 마지막 점을 지키며 균등 간격으로 [max]개 이하로 줄인다. */
        fun downsample(values: List<Double>, max: Int = CURVE_POINTS): List<Double> {
            if (values.size <= max || max < 2) return values
            val step = (values.size - 1).toDouble() / (max - 1)
            return (0 until max).map { i -> values[Math.round(i * step).toInt().coerceAtMost(values.lastIndex)] }
        }
    }

    fun summarize(ruleSetIds: Collection<String>): Map<String, StrategyPerformance> {
        val ids = ruleSetIds.distinct()
        if (ids.isEmpty()) return emptyMap()

        val backtests = backtestResultRepository.findLatestByRuleSetIds(ids).associateBy { it.ruleSetId }
        // 진행 중인 포워드가 있으면 그것을, 없으면 가장 최근에 시작한 것을 보여 준다.
        val forwards = forwardTestRepository.findAllByRuleSetIdIn(ids)
            .groupBy { it.ruleSetId }
            .mapValues { (_, list) -> list.maxWith(compareBy<QuantForwardTest>({ it.status.name == "RUNNING" }, { it.startedAt })) }

        return ids.associateWith { id ->
            StrategyPerformance(
                backtest = backtests[id]?.let { toSummary(it) },
                forward  = forwards[id]?.let { toSummary(it) },
            )
        }
    }

    private fun toSummary(r: QuantBacktestResult): BacktestSummary {
        val curve = try {
            r.equityCurveJson?.let { objectMapper.readValue<List<QuantEquityPoint>>(it) }?.map { it.equity } ?: emptyList()
        } catch (e: Exception) {
            log.warn("백테스트 곡선 파싱 실패: backtestId={} error={}", r.id, e.message)
            emptyList()
        }
        return BacktestSummary(
            id               = r.id,
            stockId          = r.stockId,
            startDate        = r.startDate,
            endDate          = r.endDate,
            totalReturn      = r.totalReturn?.toDouble(),
            annualReturn     = r.annualReturn?.toDouble(),
            mdd              = r.mdd?.toDouble(),
            sharpe           = r.sharpe?.toDouble(),
            tradeCount       = r.tradeCount,
            reliabilityScore = r.reliabilityScore,
            curve            = downsample(curve),
        )
    }

    private fun toSummary(f: QuantForwardTest) = ForwardSummary(
        status          = f.status.name,
        startedAt       = f.startedAt.toString(),
        stoppedAt       = f.stoppedAt?.toString(),
        matchRate       = f.matchRate?.toDouble(),
        matchedSignals  = f.matchedSignals,
        comparedSignals = f.comparedSignals,
    )
}
