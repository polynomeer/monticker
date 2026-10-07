package com.monticker.api.wallet.application

import com.monticker.api.paper.application.PaperRealizedPnlService
import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class ReplayEvent(
    val time: Instant,
    val type: String,
    val stockSymbol: String?,
    /** 캔들 리플레이가 이 종목의 그날 분봉을 불러온다 */
    val stockId: Long? = null,
    val qty: Int?,
    val price: BigDecimal?,
    val pnlPct: Double?,
    /** 체결 거래 id(paper_trades.id). 입출금 등 거래가 아닌 행은 null */
    val tradeId: Long? = null,
    /** 감정 태그(EmotionType 이름)·메모. 태그를 남기지 않았으면 null */
    val emotion: String? = null,
    val memo: String? = null,
    /** ADR-085 진입 출처. 판정할 수 없던 과거 거래는 null */
    val origin: String? = null,
    val originRef: Long? = null,
    /**
     * ADR-085 — 계획된 주문인가. true: 출처가 WATCH_RULE·STRATEGY·CONDITIONAL이거나 감정 태그가 PLANNED.
     * false: 직접(MANUAL) 주문이고 PLANNED 태그가 없음. null: 거래가 아니거나 출처를 판정할 수 없음(집계에서 뺀다).
     */
    val planned: Boolean? = null,
)

data class ReplaySummary(
    val totalPnl: BigDecimal,
    val tradeCount: Int,
    val bestTrade: ReplayEvent?,
    val worstTrade: ReplayEvent?,
    /** ADR-085 — 계획된 주문 수 / 판정 가능한 주문 수 × 100. 판정 가능한 주문이 없으면 null(화면 "—"). */
    val planAdherencePct: Double? = null,
    /** ADR-085 — 계획 외 주문(planned == false) 수 */
    val unplannedCount: Int = 0,
    /** ADR-085 — 계획 여부를 판정할 수 있었던 주문 수(준수율의 분모) */
    val planEvaluatedCount: Int = 0,
)

data class DailyReplayResponse(
    val date: LocalDate,
    val events: List<ReplayEvent>,
    val summary: ReplaySummary,
)

/**
 * 하루 복기. 원장 행마다 종목·거래·평균단가를 따로 읽던 N+1을 없앴다 — 거래·종목·감정 태그·실현 손익을 각각 한 번에 읽는다.
 * 실현 손익은 [PaperRealizedPnlService](이동평균법, 보유 화면 평균단가와 같은 규칙)를 쓴다. 이전엔 그 종목 전체 매수가의
 * 단순 평균(수량 가중 없음, 매도 이후 매수까지 포함)이었다.
 */
@Service
@Transactional(readOnly = true)
class ReplayService(
    private val ledgerService: LedgerService,
    private val tradeQueryService: PaperTradeQueryService,
    private val realizedPnlService: PaperRealizedPnlService,
    private val emotionTagRepo: EmotionTagRepository,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        /** ADR-085 — 이 출처의 주문은 사용자가 미리 정한 규칙·조건이 낸 주문이다. */
        val PLANNED_ORIGINS = setOf("WATCH_RULE", "STRATEGY", "CONDITIONAL")

        fun isPlanned(origin: String?, emotion: String?): Boolean? = when {
            emotion == EmotionType.PLANNED.name -> true
            origin == null -> null
            origin in PLANNED_ORIGINS -> true
            else -> false
        }
    }

    fun getDailyReplay(userId: Long, date: LocalDate): DailyReplayResponse {
        val ledgerEvents = ledgerService.getLedgerForDate(userId, date)
            .mapNotNull { e -> typeOf(e.eventType)?.let { e to it } }

        val tradeIds = ledgerEvents.mapNotNull { (e, _) -> e.paperTradeId }
        // 같은 사용자·같은 종목일 때만 거래로 본다 — ADR-047 이전 원장은 paper_trade_id에 fills.id가 있다(V43)
        val trades = tradeQueryService.findOwnedByIds(userId, tradeIds).associateBy { it.id }
        fun tradeOf(e: LedgerEventDto) = e.paperTradeId?.let { trades[it] }?.takeIf { e.stockId == null || it.stockId == e.stockId }

        val stockIds = ledgerEvents.mapNotNull { (e, _) -> e.stockId }.distinct()
        val symbols = symbolsOf(stockIds)
        val linkedIds = ledgerEvents.mapNotNull { (e, _) -> tradeOf(e)?.id }
        val tags = if (linkedIds.isEmpty()) emptyMap()
            else emotionTagRepo.findAllByUserIdAndPaperTradeIdIn(userId, linkedIds).associateBy { it.paperTradeId }
        val pnl = realizedPnlService.forSells(userId, linkedIds.filter { trades[it]?.side == "SELL" })

        var realizedPnl = BigDecimal.ZERO
        val events = ledgerEvents.map { (e, type) ->
            val t = tradeOf(e)
            val tag = t?.let { tags[it.id] }
            val r = t?.takeIf { type == "SELL" }?.let { pnl[it.id] }
            if (r != null) realizedPnl += r.pnl
            ReplayEvent(
                time = e.createdAt,
                type = type,
                stockSymbol = e.stockId?.let { symbols[it] },
                stockId = e.stockId,
                qty = t?.quantity,
                price = t?.price,
                pnlPct = r?.pnlPct,
                tradeId = t?.id,
                emotion = tag?.emotion?.name,
                memo = tag?.memo,
                origin = t?.origin,
                originRef = t?.originRef,
                planned = if (t == null) null else isPlanned(t.origin, tag?.emotion?.name),
            )
        }

        val tradePnls = events.filter { it.pnlPct != null }
        val evaluated = events.filter { it.planned != null }
        val plannedCount = evaluated.count { it.planned == true }
        val summary = ReplaySummary(
            // 그날의 실현 손익 — 이전엔 정산 − |체결| 현금 흐름이라 매수만 한 날이 손실로 보였다(리스크 일간 손실과 같은 결함)
            totalPnl = realizedPnl,
            tradeCount = events.count { it.type == "BUY" || it.type == "SELL" },
            bestTrade = tradePnls.maxByOrNull { it.pnlPct!! },
            worstTrade = tradePnls.minByOrNull { it.pnlPct!! },
            planAdherencePct = if (evaluated.isEmpty()) null else plannedCount * 100.0 / evaluated.size,
            unplannedCount = evaluated.size - plannedCount,
            planEvaluatedCount = evaluated.size,
        )
        return DailyReplayResponse(date = date, events = events, summary = summary)
    }

    private fun typeOf(eventType: String): String? = when (eventType) {
        "FILL" -> "BUY"
        "SETTLEMENT" -> "SELL"
        "DEPOSIT" -> "DEPOSIT"
        "WITHDRAWAL" -> "WITHDRAWAL"
        else -> null
    }

    private fun symbolsOf(stockIds: List<Long>): Map<Long, String> {
        if (stockIds.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, symbol FROM stocks WHERE id IN (${stockIds.joinToString(",") { "?" }})",
            { rs, _ -> rs.getLong("id") to rs.getString("symbol") },
            *stockIds.toTypedArray(),
        ).toMap()
    }
}
