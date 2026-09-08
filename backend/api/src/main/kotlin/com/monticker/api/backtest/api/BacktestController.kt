package com.monticker.api.backtest.api

import com.monticker.api.backtest.application.BacktestService
import com.monticker.api.backtest.domain.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException

@Validated
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
    @Qualifier("backtestExecutor") private val executor: Executor,
) {

    @PostMapping
    fun run(@RequestBody req: BacktestRequestDto): ResponseEntity<BacktestResult> {
        // StrategyType.valueOf 등 검증 실패는 IllegalArgumentException으로 그대로 던져
        // GlobalExceptionHandler가 400으로 처리한다.
        val request = BacktestRequest(
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

        // CompletableFuture.get()은 실행 중 던져진 예외를 ExecutionException으로 감싼다 —
        // 감싸인 채로 흘려보내면 GlobalExceptionHandler가 실제 타입(IllegalArgumentException
        // 등)으로 매칭하지 못하고 무조건 500으로 떨어진다. cause를 꺼내 다시 던져야 원래
        // 예외 타입에 맞는 상태 코드로 처리된다. RejectedExecutionException(큐 포화)은
        // supplyAsync 제출 시점에 즉시 던져지므로 감싸이지 않고 그대로 전파된다 —
        // GlobalExceptionHandler의 전용 핸들러가 429로 처리한다.
        return try {
            ResponseEntity.ok(CompletableFuture.supplyAsync({ backtestService.run(request) }, executor).get())
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
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
