package com.monticker.api.settlement.creator.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.settlement.creator.application.CreatorEarningsService
import com.monticker.api.settlement.creator.application.StrategyEarningSummary
import com.monticker.api.settlement.creator.domain.CreatorEarning
import com.monticker.api.settlement.creator.domain.CreatorPayout
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

data class PayoutRequest(
    val amount: BigDecimal,
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
)

data class EarningResponse(
    val id: Long,
    val strategyId: Long,
    val subscriberId: Long,
    val grossAmount: BigDecimal,
    val platformFee: BigDecimal,
    val netAmount: BigDecimal,
    val status: String,
    val earnedAt: Instant,
)

data class PayoutResponse(
    val id: Long,
    val amount: BigDecimal,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val status: String,
    val rejectReason: String?,
    val requestedAt: Instant,
    val processedAt: Instant?,
)

data class EarningsSummaryResponse(
    val availableBalance: BigDecimal,
    val byStrategy: List<StrategyEarningSummary>,
)

@RestController
@RequestMapping("/api/settlement/strategy")
class CreatorEarningsController(
    private val earningsService: CreatorEarningsService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    @GetMapping("/earnings")
    fun getEarnings(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<EarningResponse>> {
        val page = earningsService.getEarnings(userId(token), pageable).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    @GetMapping("/earnings/summary")
    fun getSummary(
        @RequestHeader("Authorization") token: String,
    ): ResponseEntity<EarningsSummaryResponse> {
        val uid = userId(token)
        return ResponseEntity.ok(
            EarningsSummaryResponse(
                availableBalance = earningsService.getAvailableBalance(uid),
                byStrategy       = earningsService.getEarningsByStrategy(uid),
            )
        )
    }

    @PostMapping("/payout")
    fun requestPayout(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: PayoutRequest,
    ): ResponseEntity<PayoutResponse> {
        val payout = earningsService.requestPayout(
            creatorId     = userId(token),
            amount        = req.amount,
            bankName      = req.bankName,
            accountNumber = req.accountNumber,
            accountHolder = req.accountHolder,
        )
        return ResponseEntity.ok(payout.toResponse())
    }

    @GetMapping("/payouts")
    fun getPayouts(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<PayoutResponse>> {
        val page = earningsService.getPayouts(userId(token), pageable).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    private fun CreatorEarning.toResponse() = EarningResponse(
        id           = id,
        strategyId   = strategyId,
        subscriberId = subscriberId,
        grossAmount  = grossAmount,
        platformFee  = platformFee,
        netAmount    = netAmount,
        status       = status.name,
        earnedAt     = earnedAt,
    )

    private fun CreatorPayout.toResponse() = PayoutResponse(
        id            = id,
        amount        = amount,
        bankName      = bankName,
        accountNumber = accountNumber,
        accountHolder = accountHolder,
        status        = status.name,
        rejectReason  = rejectReason,
        requestedAt   = requestedAt,
        processedAt   = processedAt,
    )
}
