package com.monticker.api.wallet.application

import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class ReceiptResponse(
    val tradeId: Long,
    val stockSymbol: String,
    val stockName: String,
    val side: String,
    val orderedAmount: BigDecimal,
    val filledAmount: BigDecimal,
    val fee: BigDecimal,
    val settledAmount: BigDecimal,
    val quantity: Int,
    val filledPrice: BigDecimal,
    val tradedAt: Instant,
    val status: String,
    val balanceBefore: BigDecimal?,
    val balanceAfter: BigDecimal?,
)

@Service
@Transactional(readOnly = true)
class ReceiptService(
    private val tradeQueryService: PaperTradeQueryService,
    private val ledgerRepo: LedgerEventRepository,
    private val jdbc: JdbcTemplate,
) {

    fun getReceipt(userId: Long, tradeId: Long): ReceiptResponse {
        // 남의 거래도 없는 거래와 같은 404·같은 메시지로 응답해 거래 id 존재 여부를 열거할 수 없게 한다
        val trade = tradeQueryService.findById(tradeId)
            ?.takeIf { it.userId == userId }
            ?: throw NoSuchElementException("Paper trade not found: $tradeId")

        val stockInfo = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", trade.stockId)
        val symbol = stockInfo["symbol"] as String
        val name = stockInfo["name"] as String

        val fee = trade.amount.multiply(BigDecimal("0.00015")).setScale(0, RoundingMode.HALF_UP)
        val settledAmount = if (trade.side == "BUY") {
            trade.amount + fee
        } else {
            trade.amount - fee
        }

        val ledgerEntry = ledgerRepo.findTopByPaperTradeIdOrderByIdDesc(tradeId)

        val balanceAfter = ledgerEntry?.balanceAfter
        val balanceBefore = if (balanceAfter != null) {
            if (trade.side == "BUY") balanceAfter + trade.amount else balanceAfter - trade.amount
        } else null

        return ReceiptResponse(
            tradeId = trade.id,
            stockSymbol = symbol,
            stockName = name,
            side = trade.side,
            orderedAmount = trade.amount,
            filledAmount = trade.amount,
            fee = fee,
            settledAmount = settledAmount,
            quantity = trade.quantity,
            filledPrice = trade.price,
            tradedAt = trade.tradedAt,
            status = "SETTLED",
            balanceBefore = balanceBefore,
            balanceAfter = balanceAfter,
        )
    }
}
