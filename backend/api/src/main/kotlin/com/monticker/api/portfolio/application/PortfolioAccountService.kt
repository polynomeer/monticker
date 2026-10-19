package com.monticker.api.portfolio.application

import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.AccountSource
import com.monticker.api.portfolio.domain.Institution
import com.monticker.api.portfolio.domain.PortfolioAccount
import com.monticker.api.portfolio.domain.PortfolioTransaction
import com.monticker.api.portfolio.domain.TransactionType
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository.NewTransaction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 손 입력 거래 한 건. 매수·매도는 수량·단가, 그 밖은 금액 */
data class ManualTransactionInput(
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
 * 통합 포트폴리오의 계좌·거래 관리(ADR-106 1단계 — 손 입력). 이용자가 직접 넣은 기록이라 지우면 남기지 않는다.
 */
@Service
class PortfolioAccountService(
    private val accounts: PortfolioAccountRepository,
    private val transactions: PortfolioTransactionRepository,
    private val jdbc: JdbcTemplate,
) {
    /** 테스트에서 "오늘"을 고정한다 */
    var clock: Clock = Clock.system(KST)

    fun list(userId: Long): List<PortfolioAccount> = accounts.findAll(userId)

    @Transactional
    fun createManual(userId: Long, name: String, institution: Institution, kind: AccountKind, refLast4: String?): PortfolioAccount {
        val trimmed = validName(name)
        val masked = refLast4?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(it.matches(Regex("\\d{4}"))) { "계좌번호는 끝 4자리 숫자만 넣는다" }
            "****$it"
        }
        if (accounts.count(userId) >= MAX_ACCOUNTS) throw BusinessRuleException("계좌는 ${MAX_ACCOUNTS}개까지 만들 수 있습니다")
        val id = accounts.insert(userId, AccountSource.MANUAL, institution, trimmed, kind, masked)
        return accounts.find(userId, id)!!
    }

    @Transactional
    fun update(userId: Long, accountId: Long, name: String, kind: AccountKind): PortfolioAccount {
        if (!accounts.update(userId, accountId, validName(name), kind)) throw NoSuchElementException("계좌를 찾을 수 없습니다")
        return accounts.find(userId, accountId)!!
    }

    @Transactional
    fun delete(userId: Long, accountId: Long) {
        if (!accounts.delete(userId, accountId)) throw NoSuchElementException("계좌를 찾을 수 없습니다")
    }

    fun transactions(userId: Long, accountId: Long, page: Int, size: Int): List<PortfolioTransaction> {
        requireAccount(userId, accountId)
        require(page >= 0 && size in 1..200) { "page는 0 이상, size는 1~200이다" }
        return transactions.findPage(userId, accountId, size, page * size)
    }

    @Transactional
    fun addManual(userId: Long, accountId: Long, input: ManualTransactionInput): Long {
        val account = requireAccount(userId, accountId)
        require(account.source == AccountSource.MANUAL) { "손 입력 계좌에만 거래를 직접 넣을 수 있습니다" }
        if (transactions.count(accountId) >= MAX_TRANSACTIONS_PER_ACCOUNT) {
            throw BusinessRuleException("계좌당 거래는 ${MAX_TRANSACTIONS_PER_ACCOUNT}건까지입니다")
        }
        // 손 입력은 같은 날 같은 거래를 두 번 넣을 수 있다(실제로 그런 매매가 있다) — 지문은 매번 새로 만든다
        return transactions.insert(accountId, normalize(input).copy(fingerprint = "manual:" + UUID.randomUUID()))
    }

    @Transactional
    fun deleteTransaction(userId: Long, accountId: Long, transactionId: Long) {
        if (!transactions.delete(userId, accountId, transactionId)) throw NoSuchElementException("거래를 찾을 수 없습니다")
    }

    /** 입력 검증·정규화 — CSV 가져오기(다음 단계)도 같은 규칙을 쓴다 */
    internal fun normalize(input: ManualTransactionInput): NewTransaction {
        val today = LocalDate.now(clock)
        require(!input.tradeDate.isAfter(today)) { "미래 날짜의 거래는 넣을 수 없습니다" }
        require(!input.tradeDate.isBefore(EARLIEST)) { "${EARLIEST} 이전 거래는 넣을 수 없습니다" }
        require(input.fee.signum() >= 0 && input.tax.signum() >= 0) { "수수료·세금은 0 이상이다" }

        val code = input.instrumentCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(code == null || code.length <= 30) { "상품 코드는 30자까지다" }
        val stock = input.stockId?.let { stockInfo(it) ?: throw IllegalArgumentException("종목을 찾을 수 없습니다: ${input.stockId}") }
        val currency = (input.currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: stock?.currency ?: "KRW").also {
            require(it.matches(Regex("[A-Z]{3}"))) { "통화는 세 글자 코드다(예: KRW, USD)" }
            require(stock == null || it == stock.currency) { "종목의 거래 통화(${stock!!.currency})와 다릅니다" }
        }
        val hasInstrument = stock != null || code != null

        val (quantity, price, amount) = when {
            input.type.isTrade -> {
                require(hasInstrument) { "매수·매도에는 종목이 필요합니다" }
                val q = requireNotNull(input.quantity) { "수량이 필요합니다" }
                val p = requireNotNull(input.price) { "단가가 필요합니다" }
                require(q.signum() > 0) { "수량은 0보다 커야 합니다" }
                require(p.signum() >= 0) { "단가는 0 이상이다" }
                require(q.scale() <= 6 && p.scale() <= 6) { "수량·단가는 소수 6자리까지다" }
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
            stockId = stock?.id, instrumentCode = if (stock != null) null else code,
            instrumentName = if (stock != null) null else input.instrumentName?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            quantity = quantity, price = price, amount = amount,
            fee = input.fee.setScale(4, RoundingMode.HALF_UP), tax = input.tax.setScale(4, RoundingMode.HALF_UP),
            currency = currency, memo = input.memo?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            fingerprint = "",
        )
    }

    private data class StockInfo(val id: Long, val currency: String)

    private fun stockInfo(stockId: Long): StockInfo? =
        jdbc.query("SELECT id, currency FROM stocks WHERE id = ?", { rs, _ -> StockInfo(rs.getLong("id"), rs.getString("currency")) }, stockId)
            .firstOrNull()

    private fun requireAccount(userId: Long, accountId: Long): PortfolioAccount =
        accounts.find(userId, accountId) ?: throw NoSuchElementException("계좌를 찾을 수 없습니다")

    private fun validName(name: String): String = name.trim().also { require(it.length in 1..50) { "계좌 이름은 1~50자다" } }

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val EARLIEST: LocalDate = LocalDate.of(1990, 1, 1)
        const val MAX_ACCOUNTS = 20
        const val MAX_TRANSACTIONS_PER_ACCOUNT = 50_000
    }
}
