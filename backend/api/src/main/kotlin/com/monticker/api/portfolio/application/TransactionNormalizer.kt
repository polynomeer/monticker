package com.monticker.api.portfolio.application

import com.monticker.api.portfolio.domain.TransactionType
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository.NewTransaction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 거래 한 건 입력(손 입력·CSV 공통). 매수·매도는 수량·단가, 그 밖은 금액 */
data class TransactionInput(
    val type: TransactionType,
    val tradeDate: LocalDate,
    val stockId: Long? = null,
    val instrumentCode: String? = null,
    val instrumentName: String? = null,
    val quantity: BigDecimal? = null,
    val price: BigDecimal? = null,
    val amount: BigDecimal? = null,
    val fee: BigDecimal = BigDecimal.ZERO,
    val tax: BigDecimal = BigDecimal.ZERO,
    val currency: String? = null,
    val memo: String? = null,
)

/**
 * 거래 입력 검증·정규화 — 손 입력과 CSV 가져오기가 같은 규칙을 쓴다. 규칙에 어긋나면 [IllegalArgumentException](400).
 * 지문(fingerprint)은 출처마다 다르게 만들므로 여기서는 비워 둔다.
 */
@Component
class TransactionNormalizer(private val jdbc: JdbcTemplate) {

    /** 테스트에서 "오늘"을 고정한다 */
    var clock: Clock = Clock.system(KST)

    fun normalize(input: TransactionInput): NewTransaction {
        val today = LocalDate.now(clock)
        require(!input.tradeDate.isAfter(today)) { "미래 날짜의 거래는 넣을 수 없습니다" }
        require(!input.tradeDate.isBefore(EARLIEST)) { "$EARLIEST 이전 거래는 넣을 수 없습니다" }
        require(input.fee.signum() >= 0 && input.tax.signum() >= 0) { "수수료·세금은 0 이상이어야 합니다" }

        val code = input.instrumentCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(code == null || code.length <= 30) { "상품 코드는 30자까지입니다" }
        val stockCurrency = input.stockId?.let { stockCurrency(it) ?: throw IllegalArgumentException("종목을 찾을 수 없습니다: ${input.stockId}") }
        val currency = (input.currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: stockCurrency ?: "KRW").also {
            require(it.matches(Regex("[A-Z]{3}"))) { "통화는 세 글자 코드입니다(예: KRW, USD)" }
            require(stockCurrency == null || it == stockCurrency) { "종목의 거래 통화($stockCurrency)와 다릅니다" }
        }
        val hasInstrument = input.stockId != null || code != null

        val (quantity, price, amount) = when {
            input.type.isTrade -> {
                require(hasInstrument) { "매수·매도에는 종목이 필요합니다" }
                val q = requireNotNull(input.quantity) { "수량이 필요합니다" }
                val p = requireNotNull(input.price) { "단가가 필요합니다" }
                require(q.signum() > 0) { "수량은 0보다 커야 합니다" }
                require(p.signum() >= 0) { "단가는 0 이상이어야 합니다" }
                require(q.stripTrailingZeros().scale() <= 6 && p.stripTrailingZeros().scale() <= 6) { "수량·단가는 소수 6자리까지입니다" }
                Triple(q, p, (input.amount ?: q.multiply(p)).setScale(4, RoundingMode.HALF_UP))
            }
            else -> {
                if (input.type == TransactionType.DIVIDEND) require(hasInstrument) { "배당에는 종목이 필요합니다" }
                val a = requireNotNull(input.amount) { "금액이 필요합니다" }
                require(a.signum() > 0) { "금액은 0보다 커야 합니다" }
                Triple(null, null, a.setScale(4, RoundingMode.HALF_UP))
            }
        }
        require(amount.precision() - amount.scale() <= 20) { "금액이 너무 큽니다" }

        return NewTransaction(
            type = input.type, tradeDate = input.tradeDate,
            stockId = input.stockId, instrumentCode = if (input.stockId != null) null else code,
            instrumentName = if (input.stockId != null) null else input.instrumentName?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            quantity = quantity, price = price, amount = amount,
            fee = input.fee.setScale(4, RoundingMode.HALF_UP), tax = input.tax.setScale(4, RoundingMode.HALF_UP),
            currency = currency, memo = input.memo?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            fingerprint = "",
        )
    }

    private fun stockCurrency(stockId: Long): String? =
        jdbc.queryForList("SELECT currency FROM stocks WHERE id = ?", String::class.java, stockId).firstOrNull()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val EARLIEST: LocalDate = LocalDate.of(1990, 1, 1)
    }
}
