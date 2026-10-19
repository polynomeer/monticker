package com.monticker.worker.notification

import com.monticker.worker.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-108 — 종목 소식을 받을 사람(관심종목 ∪ 보유)을 실제 스키마(api 마이그레이션)에서 본다.
 * 보유 판정이 api `HoldingsCalculator`와 같은지 — 특히 가진 것보다 많이 판 기록(앞 구간이 빠진 CSV)에서 — 가 핵심이다.
 */
class StockAudienceIntegrationTest : PostgresIntegrationTest() {

    companion object { private val seq = AtomicLong() }

    private fun user(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, 'aud') RETURNING id",
        Long::class.java, "aud-${seq.incrementAndGet()}-${System.nanoTime()}@test.local",
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, '보유테스트', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "H%05d".format(seq.incrementAndGet() % 100_000),
    )!!

    private fun account(userId: Long): Long = jdbcTemplate.queryForObject(
        "INSERT INTO portfolio_accounts (user_id, source, institution, name) VALUES (?, 'CSV', 'OTHER', '계좌') RETURNING id",
        Long::class.java, userId,
    )!!

    /** 입력 순서가 곧 id 순서다 — 같은 날짜 안의 처리 순서를 테스트가 정한다 */
    private fun tx(accountId: Long, stockId: Long, type: String, qty: String, date: String, currency: String = "KRW") {
        jdbcTemplate.update(
            """INSERT INTO portfolio_transactions (account_id, type, trade_date, stock_id, quantity, price, amount, currency, fingerprint)
               VALUES (?, ?, ?, ?, ?::numeric, 100, 100, ?, ?)""",
            accountId, type, LocalDate.parse(date), stockId, qty, currency, "fp-${seq.incrementAndGet()}",
        )
    }

    private fun watch(userId: Long, stockId: Long) {
        val groupId = jdbcTemplate.queryForObject(
            "INSERT INTO watchlist_groups (user_id, name) VALUES (?, 'g') RETURNING id", Long::class.java, userId,
        )!!
        jdbcTemplate.update("INSERT INTO watchlist_items (group_id, stock_id) VALUES (?, ?)", groupId, stockId)
    }

    private fun audience(stockId: Long): List<Long> =
        jdbcTemplate.queryForList(StockAudience.INTERESTED_USERS_SQL, Long::class.java, stockId, stockId).filterNotNull()

    @Test
    fun `watchers and holders are both in, each once`() {
        val s = stock()
        val watcher = user().also { watch(it, s) }
        val holder = user().also { tx(account(it), s, "BUY", "10", "2026-01-02") }
        val both = user().also { watch(it, s); tx(account(it), s, "BUY", "1", "2026-01-02"); tx(account(it), s, "BUY", "1", "2026-01-03") }
        val elsewhere = user().also { tx(account(it), stock(), "BUY", "10", "2026-01-02") }

        assertThat(audience(s)).containsExactlyInAnyOrder(watcher, holder, both).doesNotContain(elsewhere)
    }

    @Test
    fun `a fully sold position is not a holding`() {
        val s = stock()
        val sold = user().also { val a = account(it); tx(a, s, "BUY", "10", "2026-01-02"); tx(a, s, "SELL", "4", "2026-02-02"); tx(a, s, "SELL", "6", "2026-03-02") }
        val partly = user().also { val a = account(it); tx(a, s, "BUY", "10", "2026-01-02"); tx(a, s, "SELL", "9.5", "2026-02-02") }

        assertThat(audience(s)).containsExactly(partly).doesNotContain(sold)
    }

    @Test
    fun `an oversell is clamped like HoldingsCalculator - the later buy still counts`() {
        val s = stock()
        // 앞 구간이 빠진 파일: 매도 5가 먼저 있고 매수 3이 뒤에 — 계산기는 매도를 0에서 멈추고 3을 보유로 본다(합계는 -2)
        val clampedThenBought = user().also { val a = account(it); tx(a, s, "SELL", "5", "2026-01-02"); tx(a, s, "BUY", "3", "2026-02-02") }
        // 매수 3 뒤 매도 5 — 0
        val boughtThenOversold = user().also { val a = account(it); tx(a, s, "BUY", "3", "2026-01-02"); tx(a, s, "SELL", "5", "2026-02-02") }
        // 같은 날이면 입력(id) 순: 매도가 먼저 들어갔으면 0에서 멈추고 매수 5가 남는다
        val sameDay = user().also { val a = account(it); tx(a, s, "SELL", "5", "2026-01-02"); tx(a, s, "BUY", "5", "2026-01-02") }

        assertThat(audience(s)).containsExactlyInAnyOrder(clampedThenBought, sameDay).doesNotContain(boughtThenOversold)
    }

    @Test
    fun `positions are per account and currency, not netted across them`() {
        val s = stock()
        // 한 계좌에서 사고 다른 계좌에서 판 기록 — 계산기는 계좌마다 따로 본다. 첫 계좌에 5가 남는다
        val twoAccounts = user().also { tx(account(it), s, "BUY", "5", "2026-01-02"); tx(account(it), s, "SELL", "5", "2026-01-03") }
        val otherCurrency = user().also { val a = account(it); tx(a, s, "BUY", "5", "2026-01-02", "USD"); tx(a, s, "SELL", "5", "2026-01-03", "KRW") }

        assertThat(audience(s)).containsExactlyInAnyOrder(twoAccounts, otherCurrency)
    }

    @Test
    fun `dividends and cash rows alone are not holdings`() {
        val s = stock()
        val dividendOnly = user().also {
            jdbcTemplate.update(
                """INSERT INTO portfolio_transactions (account_id, type, trade_date, stock_id, amount, fingerprint)
                   VALUES (?, 'DIVIDEND', DATE '2026-01-02', ?, 1000, ?)""",
                account(it), s, "fp-${seq.incrementAndGet()}",
            )
        }

        assertThat(audience(s)).doesNotContain(dividendOnly).isEmpty()
    }
}
