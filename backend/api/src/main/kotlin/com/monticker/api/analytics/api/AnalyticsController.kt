package com.monticker.api.analytics.api

import com.monticker.api.analytics.application.CurrentPortfolioPoint
import com.monticker.api.analytics.application.HoldingsComparisonService
import com.monticker.api.analytics.application.OptimizationResult
import com.monticker.api.analytics.application.PatternRecognizerService
import com.monticker.api.analytics.application.PortfolioOptimizerService
import com.monticker.api.analytics.application.PositionSizerService
import com.monticker.api.analytics.application.RegimeDetectorService
import com.monticker.api.analytics.application.TaxOptimizerService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@Validated
@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(
    private val portfolioOptimizerService: PortfolioOptimizerService,
    private val taxOptimizerService: TaxOptimizerService,
    private val positionSizerService: PositionSizerService,
    private val holdingsComparisonService: HoldingsComparisonService,
) {
    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/portfolio/optimize")
    fun optimizePortfolio(
        @RequestParam stockIds: List<Long>,
        @RequestParam(required = false) targetReturn: Double?,
        // 사용자의 모의투자 보유 비중을 같은 축에서 비교한다. 결과(최적화)는 캐시하지만 보유는 매번 읽는다.
        @RequestParam(defaultValue = "false") compareHoldings: Boolean,
    ): ResponseEntity<OptimizeResponse> {
        val uid = userId()
        val result = portfolioOptimizerService.optimize(uid, stockIds, targetReturn)
        // V-L1 — 종목 수·데이터 부족 같은 입력 오류를 200 + error 필드로 돌려보내면 호출자가
        // error를 확인하지 않는 한 weights={}를 성공으로 취급한다. 400으로 명확히 던진다.
        if (result.error != null) throw IllegalArgumentException(result.error)
        val current = if (compareHoldings) holdingsComparisonService.compare(uid, result.stockIds) else null
        return ResponseEntity.ok(OptimizeResponse(result, current))
    }

    @GetMapping("/portfolio/frontier")
    fun getFrontier(@RequestParam stockIds: List<Long>) =
        ResponseEntity.ok(portfolioOptimizerService.getEfficientFrontier(userId(), stockIds))

    @GetMapping("/tax/harvesting-candidates")
    fun getHarvestingCandidates() =
        ResponseEntity.ok(taxOptimizerService.findHarvestingCandidates(userId()))

    @GetMapping("/position-size/kelly")
    fun getKellyForRuleSet(@RequestParam ruleSetId: String) =
        ResponseEntity.ok(positionSizerService.calculateKellyForRuleSet(ruleSetId))

    @PostMapping("/position-size/kelly")
    fun calculateKellyManual(@RequestBody req: KellyRequest) =
        ResponseEntity.ok(positionSizerService.calculateKelly(req.winRate, req.avgWinPct, req.avgLossPct))
}

/** OptimizationResult 필드를 그대로 펼치고, 요청 시 현재 보유 비교점을 붙인다(기존 클라이언트 호환). */
data class OptimizeResponse(
    @get:com.fasterxml.jackson.annotation.JsonUnwrapped val result: OptimizationResult,
    val current: CurrentPortfolioPoint?,
)

data class KellyRequest(
    val winRate: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
)

@Validated
@RestController
@RequestMapping("/api/stocks")
class StockAnalyticsController(
    private val patternRecognizerService: PatternRecognizerService,
    private val regimeDetectorService: RegimeDetectorService,
) {
    @GetMapping("/{stockId}/patterns")
    fun getPatterns(
        @PathVariable stockId: Long,
        @RequestParam(required = false, defaultValue = "90") lookbackDays: Int,
    ) = ResponseEntity.ok(patternRecognizerService.detectPatterns(stockId, lookbackDays))

    @GetMapping("/{stockId}/regime")
    fun getRegime(@PathVariable stockId: Long) =
        ResponseEntity.ok(regimeDetectorService.classifyRegime(stockId))
}
