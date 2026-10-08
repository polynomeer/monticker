package com.monticker.api.analytics.application

import com.monticker.api.paper.application.PaperPortfolioQueryService
import org.springframework.stereotype.Service
import java.math.BigDecimal

/** 사용자의 현재(모의투자) 포트폴리오를 최적화와 같은 축에 놓은 점. 비중은 0~1. */
data class CurrentPortfolioPoint(
    /** 분석 대상 종목 안에서 평가금액 비중(합 1) */
    val weights: Map<Long, Double>,
    val expectedReturn: Double,
    val expectedRisk: Double,
    /** 보유 주식 평가금액 중 분석 대상 종목이 차지하는 비율 — 1보다 작으면 일부 보유 종목이 비교에서 빠졌다 */
    val coveredValueRatio: Double,
    /** 분석 대상이지만 보유하지 않은 종목 */
    val notHeld: List<Long>,
)

/**
 * /analytics의 "현재 포트폴리오 비중 비교". 예전에는 비교 기준이 동일가중뿐이었다.
 * 모의투자 보유(paper::api)를 평가금액 비중으로 바꿔 최적 비중과 같은 기대수익·위험 축에 놓는다.
 * 현금은 제외한다 — 최적화도 주식 비중만 다룬다.
 */
@Service
class HoldingsComparisonService(
    private val paperPortfolio: PaperPortfolioQueryService,
    private val optimizerQuery: PortfolioOptimizerQueryService,
) {
    fun compare(userId: Long, stockIds: List<Long>, period: AnalysisPeriod): CurrentPortfolioPoint? {
        val ids = stockIds.distinct()
        val holdings = paperPortfolio.buildHoldings(userId).filter { it.value > BigDecimal.ZERO }
        if (holdings.isEmpty()) return null

        val totalValue = holdings.fold(BigDecimal.ZERO) { a, h -> a + h.value }
        val inScope = holdings.filter { it.stockId in ids }
        val scopeValue = inScope.fold(BigDecimal.ZERO) { a, h -> a + h.value }
        if (scopeValue <= BigDecimal.ZERO) return null

        val weights = inScope.associate { it.stockId to it.value.toDouble() / scopeValue.toDouble() }
        val (ret, risk) = optimizerQuery.evaluateWeights(ids, weights, period) ?: return null
        return CurrentPortfolioPoint(
            weights           = weights,
            expectedReturn    = ret,
            expectedRisk      = risk,
            coveredValueRatio = scopeValue.toDouble() / totalValue.toDouble(),
            notHeld           = ids.filter { it !in weights },
        )
    }
}
