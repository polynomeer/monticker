package com.monticker.api.backtest.api

import com.monticker.api.backtest.application.BacktestService
import com.monticker.api.backtest.domain.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@Validated
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
    @Qualifier("backtestExecutor") private val executor: Executor,
) {

    @PostMapping
    fun run(@RequestBody req: BacktestRequestDto): ResponseEntity<*> {
        val request = try {
            BacktestRequest(
                stockId           = req.stockId,
                strategy          = StrategyType.valueOf(req.strategy),
                fromDate          = req.fromDate,
                toDate            = req.toDate,
                initialCapital    = req.initialCapital ?: 10_000_000.0,
                shortPeriod       = req.shortPeriod   ?: 5,
                longPeriod        = req.longPeriod    ?: 20,
                rsiPeriod         = req.rsiPeriod     ?: 14,
                rsiOversold       = req.rsiOversold   ?: 30.0,
                rsiOverbought     = req.rsiOverbought ?: 70.0,
                emaPeriod         = req.emaPeriod     ?: 20,
                breakoutMultiplier = req.breakoutMultiplier ?: 1.5,
                stopLossPct       = req.stopLossPct   ?: 5.0,
                takeProfitPct     = req.takeProfitPct ?: 10.0,
            )
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

        return try {
            val result = CompletableFuture.supplyAsync({ backtestService.run(request) }, executor).get()
            ResponseEntity.ok(result)
        } catch (e: RejectedExecutionException) {
            ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "백테스트 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."))
        } catch (e: Exception) {
            val cause = e.cause ?: e
            if (cause is IllegalArgumentException)
                ResponseEntity.badRequest().body(mapOf("error" to cause.message))
            else
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(mapOf("error" to cause.message))
        }
    }

    @GetMapping("/strategies")
    fun strategies() = ResponseEntity.ok(
        StrategyType.values().map { mapOf("key" to it.name, "label" to strategyLabel(it)) }
    )

    private fun strategyLabel(s: StrategyType) = when (s) {
        StrategyType.MA_CROSSOVER -> "이동평균 크로스오버"
        StrategyType.RSI          -> "RSI 과매수/과매도"
        StrategyType.EMA_BREAKOUT -> "EMA 돌파 전략"
    }
}

data class BacktestRequestDto(
    val stockId: Long,
    val strategy: String,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) val fromDate: LocalDate,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) val toDate: LocalDate,
    val initialCapital: Double? = null,
    val shortPeriod: Int? = null,
    val longPeriod: Int? = null,
    val rsiPeriod: Int? = null,
    val rsiOversold: Double? = null,
    val rsiOverbought: Double? = null,
    val emaPeriod: Int? = null,
    val breakoutMultiplier: Double? = null,
    val stopLossPct: Double? = null,
    val takeProfitPct: Double? = null,
)
