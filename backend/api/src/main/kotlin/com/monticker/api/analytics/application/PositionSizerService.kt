package com.monticker.api.analytics.application

import com.monticker.api.quant.infrastructure.QuantBacktestResultRepository
import org.springframework.stereotype.Service

data class KellyResult(
    val winRate: Double,
    val avgWin: Double,
    val avgLoss: Double,
    val fullKelly: Double,
    val halfKelly: Double,
    val recommendation: String,
)

@Service
class PositionSizerService(
    private val backtestResultRepository: QuantBacktestResultRepository,
) {
    fun calculateKelly(winRate: Double, avgWinPct: Double, avgLossPct: Double): KellyResult {
        val full = kellyFraction(winRate, avgWinPct, avgLossPct)
        val half = full / 2.0
        val recommendation = when {
            full <= 0.0 -> "켈리 비율이 0 이하입니다. 이 전략으로는 포지션을 잡지 않는 것을 권장합니다."
            full < 0.05 -> "켈리 비율이 매우 낮습니다. 보수적으로 소액 포지션을 권장합니다 (Half Kelly: ${"%.1f".format(half * 100)}%)."
            full > 0.5 -> "켈리 비율이 높지만 변동성 위험을 고려해 Half Kelly(${"%.1f".format(half * 100)}%) 이하 사용을 권장합니다."
            else -> "권장 포지션 크기는 자본의 ${"%.1f".format(half * 100)}% (Half Kelly) 입니다."
        }
        return KellyResult(winRate, avgWinPct, avgLossPct, full, half, recommendation)
    }

    fun calculateKellyForRuleSet(ruleSetId: String): KellyResult {
        val results = backtestResultRepository.findAllByRuleSetId(ruleSetId)
        val latest = results.maxByOrNull { it.createdAt }
            ?: return calculateKelly(0.0, 0.0, 0.0).copy(
                recommendation = "백테스트 결과가 없습니다. 먼저 백테스트를 실행해주세요."
            )

        val winRate = (latest.winRate?.toDouble() ?: 0.0) / 100.0
        val profitFactor = latest.profitFactor?.toDouble()

        // profitFactor = (winRate * avgWin) / ((1 - winRate) * avgLoss)
        // Without separate avgWin/avgLoss, approximate avgLoss = 1.0 (unit loss) and derive avgWin from profitFactor.
        val avgLoss = 1.0
        val avgWin = if (profitFactor != null && winRate > 0.0 && winRate < 1.0) {
            (profitFactor * (1 - winRate) * avgLoss) / winRate
        } else {
            1.5 * avgLoss // fallback fixed ratio when data is insufficient
        }

        return calculateKelly(winRate, avgWin, avgLoss)
    }

    fun kellyFraction(winRate: Double, avgWin: Double, avgLoss: Double): Double {
        if (avgLoss <= 0) return 0.0
        val b = avgWin / avgLoss
        if (b <= 0) return 0.0
        val p = winRate
        val q = 1 - p
        val f = (b * p - q) / b
        return f.coerceIn(0.0, 1.0)
    }
}
