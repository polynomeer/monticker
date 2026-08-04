package com.monticker.api.paper.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.paper.application.PaperSettlementService
import com.monticker.api.paper.domain.PaperSettlement
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class PaperSettlementResponse(
    val id: Long,
    val tradeId: Long,
    val stockId: Long,
    val side: String,
    val quantity: Int,
    val fillPrice: BigDecimal,
    val grossAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val netAmount: BigDecimal,
    val status: String,
    val settleDate: LocalDate,
    val settledAt: Instant?,
    val createdAt: Instant,
)

@RestController
@RequestMapping("/api/settlement/paper")
class PaperSettlementController(
    private val settlementService: PaperSettlementService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    @GetMapping
    fun getSettlements(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<PaperSettlementResponse>> {
        val page = settlementService.getSettlements(userId(token), pageable).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    @GetMapping("/pending")
    fun getPendingSettlements(
        @RequestHeader("Authorization") token: String,
    ): ResponseEntity<List<PaperSettlementResponse>> {
        val list = settlementService.getPendingSettlements(userId(token)).map { it.toResponse() }
        return ResponseEntity.ok(list)
    }

    @GetMapping("/trade/{tradeId}")
    fun getByTradeId(
        @RequestHeader("Authorization") token: String,
        @PathVariable tradeId: Long,
    ): ResponseEntity<PaperSettlementResponse> {
        val settlement = settlementService.getByTradeId(tradeId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(settlement.toResponse())
    }

    private fun PaperSettlement.toResponse() = PaperSettlementResponse(
        id          = id,
        tradeId     = tradeId,
        stockId     = stockId,
        side        = side,
        quantity    = quantity,
        fillPrice   = fillPrice,
        grossAmount = grossAmount,
        fee         = fee,
        tax         = tax,
        netAmount   = netAmount,
        status      = status.name,
        settleDate  = settleDate,
        settledAt   = settledAt,
        createdAt   = createdAt,
    )
}
