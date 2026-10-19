package com.monticker.api.portfolio.infrastructure

import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.AccountSource
import com.monticker.api.portfolio.domain.Institution
import com.monticker.api.portfolio.domain.PortfolioAccount
import com.monticker.api.portfolio.domain.PortfolioTransaction
import com.monticker.api.portfolio.domain.TransactionType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Date
import java.time.LocalDate

/**
 * 계좌 조회는 **언제나 user_id로 거른다** — 남의 계좌는 없는 계좌와 똑같이 보인다(404).
 */
@Repository
class PortfolioAccountRepository(private val jdbc: JdbcTemplate) {

    private val mapper = RowMapper { rs, _ ->
        PortfolioAccount(
            id = rs.getLong("id"),
            userId = rs.getLong("user_id"),
            source = AccountSource.valueOf(rs.getString("source")),
            institution = Institution.valueOf(rs.getString("institution")),
            name = rs.getString("name"),
            accountKind = AccountKind.valueOf(rs.getString("account_kind")),
            accountRefMasked = rs.getString("account_ref_masked"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    fun findAll(userId: Long): List<PortfolioAccount> =
        jdbc.query("SELECT * FROM portfolio_accounts WHERE user_id = ? ORDER BY id", mapper, userId)

    fun find(userId: Long, accountId: Long): PortfolioAccount? =
        jdbc.query("SELECT * FROM portfolio_accounts WHERE id = ? AND user_id = ?", mapper, accountId, userId).firstOrNull()

    fun count(userId: Long): Int =
        jdbc.queryForObject("SELECT count(*) FROM portfolio_accounts WHERE user_id = ?", Int::class.java, userId) ?: 0

    fun insert(
        userId: Long, source: AccountSource, institution: Institution, name: String, kind: AccountKind, refMasked: String?,
    ): Long = jdbc.queryForObject(
        """
        INSERT INTO portfolio_accounts (user_id, source, institution, name, account_kind, account_ref_masked)
        VALUES (?, ?, ?, ?, ?, ?) RETURNING id
        """.trimIndent(),
        Long::class.java, userId, source.name, institution.name, name, kind.name, refMasked,
    )!!

    fun update(userId: Long, accountId: Long, name: String, kind: AccountKind): Boolean = jdbc.update(
        "UPDATE portfolio_accounts SET name = ?, account_kind = ?, updated_at = now() WHERE id = ? AND user_id = ?",
        name, kind.name, accountId, userId,
    ) == 1

    /** 거래도 함께 지워진다(ON DELETE CASCADE) — 이용자가 지우면 남기지 않는다 */
    fun delete(userId: Long, accountId: Long): Boolean =
        jdbc.update("DELETE FROM portfolio_accounts WHERE id = ? AND user_id = ?", accountId, userId) == 1
}

@Repository
class PortfolioTransactionRepository(private val jdbc: JdbcTemplate) {

    private val mapper = RowMapper { rs, _ ->
        PortfolioTransaction(
            id = rs.getLong("id"),
            accountId = rs.getLong("account_id"),
            type = TransactionType.valueOf(rs.getString("type")),
            tradeDate = rs.getDate("trade_date").toLocalDate(),
            stockId = rs.getLong("stock_id").takeUnless { rs.wasNull() },
            instrumentCode = rs.getString("instrument_code"),
            instrumentName = rs.getString("instrument_name"),
            quantity = rs.getBigDecimal("quantity"),
            price = rs.getBigDecimal("price"),
            amount = rs.getBigDecimal("amount"),
            fee = rs.getBigDecimal("fee"),
            tax = rs.getBigDecimal("tax"),
            currency = rs.getString("currency"),
            memo = rs.getString("memo"),
        )
    }

    /** 이용자의 모든 계좌 거래(보유 계산용) — 계좌 소유는 JOIN으로 거른다 */
    fun findAllForUser(userId: Long): List<PortfolioTransaction> = jdbc.query(
        """
        SELECT t.* FROM portfolio_transactions t
        JOIN portfolio_accounts a ON a.id = t.account_id
        WHERE a.user_id = ?
        ORDER BY t.account_id, t.trade_date, t.id
        """.trimIndent(),
        mapper, userId,
    )

    fun findPage(userId: Long, accountId: Long, limit: Int, offset: Int): List<PortfolioTransaction> = jdbc.query(
        """
        SELECT t.* FROM portfolio_transactions t
        JOIN portfolio_accounts a ON a.id = t.account_id
        WHERE a.user_id = ? AND t.account_id = ?
        ORDER BY t.trade_date DESC, t.id DESC
        LIMIT ? OFFSET ?
        """.trimIndent(),
        mapper, userId, accountId, limit, offset,
    )

    fun count(accountId: Long): Int =
        jdbc.queryForObject("SELECT count(*) FROM portfolio_transactions WHERE account_id = ?", Int::class.java, accountId) ?: 0

    data class NewTransaction(
        val type: TransactionType,
        val tradeDate: LocalDate,
        val stockId: Long?,
        val instrumentCode: String?,
        val instrumentName: String?,
        val quantity: BigDecimal?,
        val price: BigDecimal?,
        val amount: BigDecimal,
        val fee: BigDecimal,
        val tax: BigDecimal,
        val currency: String,
        val memo: String?,
        val fingerprint: String,
    )

    /** 계좌 소유 확인은 호출하는 쪽(서비스)이 먼저 한다 */
    fun insert(accountId: Long, t: NewTransaction): Long = jdbc.queryForObject(
        """
        INSERT INTO portfolio_transactions
            (account_id, type, trade_date, stock_id, instrument_code, instrument_name, quantity, price, amount, fee, tax, currency, memo, fingerprint)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
        """.trimIndent(),
        Long::class.java,
        accountId, t.type.name, Date.valueOf(t.tradeDate), t.stockId, t.instrumentCode, t.instrumentName,
        t.quantity, t.price, t.amount, t.fee, t.tax, t.currency, t.memo, t.fingerprint,
    )!!

    fun delete(userId: Long, accountId: Long, transactionId: Long): Boolean = jdbc.update(
        """
        DELETE FROM portfolio_transactions t USING portfolio_accounts a
        WHERE t.id = ? AND t.account_id = ? AND a.id = t.account_id AND a.user_id = ?
        """.trimIndent(),
        transactionId, accountId, userId,
    ) == 1
}
