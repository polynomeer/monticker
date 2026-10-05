package com.monticker.api.wallet.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReplayEvent(
    val time: Instant,
    val type: String,
    val stockSymbol: String?,
    /** 캔들 리플레이가 이 종목의 그날 분봉을 불러온다 */
    val stockId: Long? = null,
    val qty: Int?,
    val price: BigDecimal?,
    val pnlPct: Double?,
)

data class ReplaySummary(
    val totalPnl: BigDecimal,
    val tradeCount: Int,
    val bestTrade: ReplayEvent?,
    val worstTrade: ReplayEvent?,
)

data class DailyReplayResponse(
    val date: LocalDate,
    val events: List<ReplayEvent>,
    val summary: ReplaySummary,
)

@Service
@Transactional(readOnly = true)
class ReplayService(
    private val ledgerService: LedgerService,
    private val jdbc: JdbcTemplate,
) {

    fun getDailyReplay(userId: Long, date: LocalDate): DailyReplayResponse {
        val ledgerEvents = ledgerService.getLedgerForDate(userId, date)

        // 매도 체결의 실현 손익(원) — 요약 totalPnl용. 매수는 손익이 아니므로 담지 않는다
        var realizedPnl = BigDecimal.ZERO

        val events = ledgerEvents.mapNotNull { event ->
            val type = when (event.eventType) {
                "FILL" -> "BUY"
                "SETTLEMENT" -> "SELL"
                "DEPOSIT" -> "DEPOSIT"
                "WITHDRAWAL" -> "WITHDRAWAL"
                else -> return@mapNotNull null
            }

            val (symbol, qty, price, pnlPct) = if (event.paperTradeId != null && event.stockId != null) {
                val stockInfo = runCatching {
                    jdbc.queryForMap("SELECT symbol FROM stocks WHERE id = ?", event.stockId)
                }.getOrNull()
                val tradeInfo = runCatching {
                    jdbc.queryForMap(
                        "SELECT quantity, price FROM paper_trades WHERE id = ?",
                        event.paperTradeId
                    )
                }.getOrNull()

                val tradeQty = tradeInfo?.get("quantity") as? Int
                val tradePrice = tradeInfo?.get("price") as? BigDecimal

                val pnl = if (type == "SELL" && tradePrice != null) {
                    val avgBuy = runCatching {
                        jdbc.queryForObject(
                            "SELECT AVG(price) FROM paper_trades WHERE user_id=? AND stock_id=? AND side='BUY'",
                            BigDecimal::class.java, userId, event.stockId
                        )
                    }.getOrNull()
                    if (avgBuy != null && avgBuy > BigDecimal.ZERO) {
                        if (tradeQty != null) realizedPnl += tradePrice.subtract(avgBuy).multiply(BigDecimal(tradeQty))
                        tradePrice.subtract(avgBuy).divide(avgBuy, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(BigDecimal("100")).toDouble()
                    } else null
                } else null

                Quadruple(stockInfo?.get("symbol") as? String, tradeQty, tradePrice, pnl)
            } else {
                Quadruple(null, null, null, null)
            }

            ReplayEvent(
                time = event.createdAt,
                type = type,
                stockSymbol = symbol,
                stockId = event.stockId,
                qty = qty,
                price = price,
                pnlPct = pnlPct,
            )
        }

        val tradePnls = events.filter { it.pnlPct != null }
        // 그날의 실현 손익 — 이전엔 정산 − |체결| 현금 흐름이라 매수만 한 날이 손실로 보였다(리스크 일간 손실과 같은 결함)
        val totalPnl = realizedPnl

        val summary = ReplaySummary(
            totalPnl = totalPnl,
            tradeCount = events.count { it.type == "BUY" || it.type == "SELL" },
            bestTrade = tradePnls.maxByOrNull { it.pnlPct!! },
            worstTrade = tradePnls.minByOrNull { it.pnlPct!! },
        )

        return DailyReplayResponse(date = date, events = events, summary = summary)
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
