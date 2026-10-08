package com.monticker.api.brokerage.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.application.RebalanceExecutionService
import com.monticker.api.brokerage.application.RebalanceLegPlan
import com.monticker.api.brokerage.application.RebalancePreview
import com.monticker.api.brokerage.application.RebalanceTargetService
import com.monticker.api.brokerage.domain.BrokerageFeeModel
import com.monticker.api.brokerage.domain.RebalanceExecution
import com.monticker.api.brokerage.domain.RebalanceExecutionLeg
import com.monticker.api.brokerage.domain.RebalanceTarget
import com.monticker.api.brokerage.domain.RebalanceTargetSource
import com.monticker.api.common.aop.RateLimited
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

// ── 요청/응답 DTO ─────────────────────────────────────────────────────────────

data class SaveRebalanceTargetRequest(
    @field:NotEmpty val weights: Map<String, BigDecimal>,
    @field:DecimalMin("0.01") @field:DecimalMax("100.00") val thresholdPct: BigDecimal = BigDecimal("5.00"),
    @field:NotBlank val source: String = "MANUAL",
)

data class RebalanceTargetResponse(
    val id: Long,
    val weights: Map<String, BigDecimal>,
    val thresholdPct: BigDecimal,
    val source: String,
    val updatedAt: Instant,
)

data class RebalanceLegResponse(
    val symbol: String,
    val side: String,
    val targetWeight: BigDecimal,
    val currentWeight: BigDecimal,
    val diffPct: BigDecimal,
    val quantity: Int,
    /** 수량을 계산한 가격(추정). 실행은 시장가 주문이다 — 이 가격으로 주문하지 않는다. */
    val estimatedPrice: BigDecimal,
    /** BROKER_BALANCE(증권사 잔고의 현재가) | LAST_CANDLE(최근 1분봉 종가) */
    val priceSource: String,
    val estimatedAmount: BigDecimal,
    val estimatedFee: BigDecimal,
    val estimatedTax: BigDecimal,
)

/** 예상 거래비용 계산 근거 — 화면에 "추정"으로 표시한다. 증권사 견적이 아니다. */
data class RebalanceCostModelResponse(
    val feeRate: BigDecimal,
    val sellTaxRate: BigDecimal,
    val basis: String = "ESTIMATE",
    val note: String = "정산과 같은 수수료(0.015%)·매도 거래세(0.18%) 식을 미리보기 가격에 적용한 추정치입니다. 시장가 체결가·증권사별 수수료율에 따라 달라집니다.",
)

data class RebalancePreviewResponse(
    val totalValue: BigDecimal,
    val legs: List<RebalanceLegResponse>,
    val estimatedFee: BigDecimal,
    val estimatedTax: BigDecimal,
    /** 예상 거래비용 = 수수료 + 매도 거래세 */
    val estimatedCost: BigDecimal,
    val estimatedBuyAmount: BigDecimal,
    val estimatedSellAmount: BigDecimal,
    /** 매수 중 지금 보유하지 않은 종목의 예상 금액 */
    val estimatedNewBuyAmount: BigDecimal,
    val costModel: RebalanceCostModelResponse,
)

data class RebalanceExecutionResponse(
    val id: Long,
    val status: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val legs: List<RebalanceExecutionLegResponse>,
)

data class RebalanceExecutionLegResponse(
    val symbol: String,
    val side: String,
    val quantity: Int,
    val status: String,
    val executedOrderId: Long?,
    val failReason: String?,
)

// ── 컨트롤러 ───────────────────────────────────────────────────────────────────

/** ADR-034 — 리밸런싱 실행 자동화(실브로커리지, 수동 실행). */
@RestController
@RequestMapping("/api/rebalance")
class RebalanceController(
    private val targetService: RebalanceTargetService,
    private val executionService: RebalanceExecutionService,
    private val brokerageService: BrokerageService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    @PostMapping("/target")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "rebalance.target")
    fun saveTarget(
        @RequestHeader("Authorization") token: String,
        @Valid @RequestBody req: SaveRebalanceTargetRequest,
    ): ResponseEntity<RebalanceTargetResponse> {
        val target = targetService.save(
            userId(token), req.weights, req.thresholdPct, RebalanceTargetSource.valueOf(req.source.uppercase()),
        )
        return ResponseEntity.ok(target.toResponse(targetService.parseWeights(target)))
    }

    @GetMapping("/target")
    fun getTarget(@RequestHeader("Authorization") token: String): ResponseEntity<RebalanceTargetResponse?> {
        val target = targetService.get(userId(token)) ?: return ResponseEntity.ok(null)
        return ResponseEntity.ok(target.toResponse(targetService.parseWeights(target)))
    }

    @GetMapping("/preview")
    fun preview(@RequestHeader("Authorization") token: String): ResponseEntity<RebalancePreviewResponse> =
        ResponseEntity.ok(executionService.preview(userId(token)).toResponse())

    @PostMapping("/execute")
    @RateLimited(limit = 10, windowSec = 60, keyPrefix = "rebalance.execute")
    fun execute(@RequestHeader("Authorization") token: String): ResponseEntity<RebalanceExecutionResponse> {
        val uid = userId(token)
        brokerageService.requireCurrentConsents(uid)   // ADR-068
        val execution = executionService.execute(uid)
        return ResponseEntity.ok(execution.toResponse(executionService.getLegs(execution.id)))
    }

    @GetMapping("/executions")
    fun getExecutions(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<RebalanceExecutionResponse>> {
        val page = executionService.getExecutions(userId(token), pageable)
            .map { it.toResponse(executionService.getLegs(it.id)) }
        return ResponseEntity.ok(page)
    }

    private fun RebalanceTarget.toResponse(weights: Map<String, BigDecimal>) = RebalanceTargetResponse(
        id = id, weights = weights, thresholdPct = thresholdPct, source = source.name, updatedAt = updatedAt,
    )

    private fun RebalancePreview.toResponse() = RebalancePreviewResponse(
        totalValue = totalValue,
        legs = legs.map { it.toResponse() },
        estimatedFee = estimatedFee,
        estimatedTax = estimatedTax,
        estimatedCost = estimatedCost,
        estimatedBuyAmount = estimatedBuyAmount,
        estimatedSellAmount = estimatedSellAmount,
        estimatedNewBuyAmount = estimatedNewBuyAmount,
        costModel = RebalanceCostModelResponse(BrokerageFeeModel.FEE_RATE, BrokerageFeeModel.SELL_TAX_RATE),
    )

    private fun RebalanceLegPlan.toResponse() = RebalanceLegResponse(
        symbol = symbol, side = side.name, targetWeight = targetWeight, currentWeight = currentWeight,
        diffPct = diffPct, quantity = quantity,
        estimatedPrice = price, priceSource = priceSource.name,
        estimatedAmount = estimatedAmount, estimatedFee = estimatedFee, estimatedTax = estimatedTax,
    )

    private fun RebalanceExecution.toResponse(legs: List<RebalanceExecutionLeg>) = RebalanceExecutionResponse(
        id = id, status = status.name, requestedAt = requestedAt, completedAt = completedAt,
        legs = legs.map { it.toResponse() },
    )

    private fun RebalanceExecutionLeg.toResponse() = RebalanceExecutionLegResponse(
        symbol = symbol, side = side.name, quantity = quantity, status = status.name,
        executedOrderId = executedOrderId, failReason = failReason,
    )
}
