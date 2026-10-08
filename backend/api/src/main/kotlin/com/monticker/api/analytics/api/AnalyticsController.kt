package com.monticker.api.analytics.api

import com.monticker.api.analytics.application.AnalysisPeriod
import com.monticker.api.analytics.application.CurrentPortfolioPoint
import com.monticker.api.analytics.application.FrontierAnalysis
import com.monticker.api.analytics.application.PortfolioSampling
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate
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

    /**
     * 분석 기간(ADR-097): `period`=3M·6M·1Y(기본)·2Y, 또는 `period=CUSTOM&from=YYYY-MM-DD&to=YYYY-MM-DD`(KST, 60일~3년).
     */
    @GetMapping("/portfolio/optimize")
    fun optimizePortfolio(
        @RequestParam stockIds: List<Long>,
        @RequestParam(required = false) targetReturn: Double?,
        // 사용자의 모의투자 보유 비중을 같은 축에서 비교한다. 결과(최적화)는 캐시하지만 보유는 매번 읽는다.
        @RequestParam(defaultValue = "false") compareHoldings: Boolean,
        @RequestParam(required = false) period: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<OptimizeResponse> {
        val uid = userId()
        val analysisPeriod = AnalysisPeriod.resolve(period, from, to)
        val result = portfolioOptimizerService.optimize(uid, stockIds, targetReturn, analysisPeriod)
        // V-L1 — 종목 수·데이터 부족 같은 입력 오류를 200 + error 필드로 돌려보내면 호출자가
        // error를 확인하지 않는 한 weights={}를 성공으로 취급한다. 400으로 명확히 던진다.
        if (result.error != null) throw IllegalArgumentException(result.error)
        val current = if (compareHoldings) holdingsComparisonService.compare(uid, result.stockIds, analysisPeriod) else null
        return ResponseEntity.ok(OptimizeResponse(result, current))
    }

    /**
     * 효율적 프론티어 + 무작위 롱 온리 포트폴리오 표본(`samples`, 100~2000, 기본 1000, 고정 seed) + 샤프 비율 최대 지점.
     * 기간 파라미터는 /portfolio/optimize와 같다. 종목 수·기간·표본 수 오류, 겹치는 거래일 부족은 400.
     */
    @GetMapping("/portfolio/frontier")
    fun getFrontier(
        @RequestParam stockIds: List<Long>,
        @RequestParam(required = false) period: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "${PortfolioSampling.DEFAULT_SAMPLES}") samples: Int,
    ): ResponseEntity<FrontierAnalysis> =
        ResponseEntity.ok(
            portfolioOptimizerService.getEfficientFrontier(userId(), stockIds, AnalysisPeriod.resolve(period, from, to), samples),
        )

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
