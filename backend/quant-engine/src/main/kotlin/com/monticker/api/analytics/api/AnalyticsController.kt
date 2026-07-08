package com.monticker.api.analytics.api

import com.monticker.api.analytics.application.PatternRecognizerService
import com.monticker.api.analytics.application.PortfolioOptimizerService
import com.monticker.api.analytics.application.PositionSizerService
import com.monticker.api.analytics.application.RegimeDetectorService
import com.monticker.api.analytics.application.TaxOptimizerService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(
    private val portfolioOptimizerService: PortfolioOptimizerService,
    private val taxOptimizerService: TaxOptimizerService,
    private val positionSizerService: PositionSizerService,
) {
    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/portfolio/optimize")
    fun optimizePortfolio(
        @RequestParam stockIds: List<Long>,
        @RequestParam(required = false) targetReturn: Double?,
    ) = ResponseEntity.ok(portfolioOptimizerService.optimize(userId(), stockIds, targetReturn))

    @GetMapping("/portfolio/frontier")
    fun getFrontier(@RequestParam stockIds: List<Long>) =
        ResponseEntity.ok(portfolioOptimizerService.getEfficientFrontier(userId(), stockIds))

    @GetMapping("/tax/harvesting-candidates")
    fun getHarvestingCandidates() =
        ResponseEntity.ok(taxOptimizerService.findHarvestingCandidates(userId()))

    @GetMapping("/position-size/kelly")
    fun getKellyForRuleSet(@RequestParam ruleSetId: Long) =
        ResponseEntity.ok(positionSizerService.calculateKellyForRuleSet(ruleSetId))

    @PostMapping("/position-size/kelly")
    fun calculateKellyManual(@RequestBody req: KellyRequest) =
        ResponseEntity.ok(positionSizerService.calculateKelly(req.winRate, req.avgWinPct, req.avgLossPct))
}

data class KellyRequest(
    val winRate: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
)

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
