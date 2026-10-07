package com.monticker.api.paper.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.paper.application.PaperSettlementService
import com.monticker.api.paper.application.PaperSettlementSummary
import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.domain.SettlementStatus
import org.springframework.format.annotation.DateTimeFormat
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

data class SettlementDayResponse(val date: LocalDate, val net: BigDecimal, val count: Int, val holidayName: String?)
data class SettlementHolidayResponse(val date: LocalDate, val name: String)

data class PaperSettlementSummaryResponse(
    val from: LocalDate,
    val to: LocalDate,
    val pendingNet: BigDecimal,
    val settledNet: BigDecimal,
    val totalNet: BigDecimal,
    val count: Int,
    val byDate: List<SettlementDayResponse>,
    /** 기간 안의 평일 휴장일 — 이날은 정산하지 않는다 */
    val holidays: List<SettlementHolidayResponse>,
) {
    companion object {
        fun from(s: PaperSettlementSummary) = PaperSettlementSummaryResponse(
            from = s.from, to = s.to,
            pendingNet = s.pendingNet, settledNet = s.settledNet, totalNet = s.totalNet, count = s.count,
            byDate = s.byDate.map { SettlementDayResponse(it.date, it.net, it.count, it.holidayName) },
            holidays = s.holidays.map { (d, n) -> SettlementHolidayResponse(d, n) },
        )
    }
}

@RestController
@RequestMapping("/api/settlement/paper")
class PaperSettlementController(
    private val settlementService: PaperSettlementService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    /** GET /api/settlement/paper?status=SETTLED — ADR-086, 상태 필터는 서버에서(페이지가 섞이지 않게). 모르는 값은 400. */
    @GetMapping
    fun getSettlements(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<Page<PaperSettlementResponse>> {
        val filter = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { SettlementStatus.valueOf(it.uppercase()) }.getOrNull() ?: return ResponseEntity.badRequest().build()
        }
        val page = settlementService.getSettlements(userId(token), pageable, filter).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    /** GET /api/settlement/paper/summary?from=&to= — 기간(정산일 기준) 순액. 생략하면 이번 주(KST 월~일). */
    @GetMapping("/summary")
    fun getSummary(
        @RequestHeader("Authorization") token: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<PaperSettlementSummaryResponse> = try {
        ResponseEntity.ok(PaperSettlementSummaryResponse.from(settlementService.getSummary(userId(token), from, to)))
    } catch (e: IllegalArgumentException) {
        ResponseEntity.badRequest().build()
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
        val settlement = settlementService.getByTradeId(userId(token), tradeId)
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
