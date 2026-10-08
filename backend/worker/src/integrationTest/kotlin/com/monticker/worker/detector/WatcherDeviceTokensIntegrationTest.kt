package com.monticker.worker.detector

import com.monticker.worker.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 관심종목 이벤트 푸시 대상 쿼리를 실제 스키마(api 마이그레이션)에 실행한다.
 * 예전 쿼리는 존재하지 않는 컬럼으로 조인해 매번 SQL 오류였는데, 목 기반 단위 테스트로는 잡히지 않았다.
 */
class WatcherDeviceTokensIntegrationTest : PostgresIntegrationTest() {

    private fun user(nick: String, deleted: Boolean = false): Long {
        val id = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "$nick-${System.nanoTime()}@test.local", nick,
        )!!
        if (deleted) jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", id)
        return id
    }

    private fun stock(symbol: String): Long = jdbcTemplate.queryForObject(
        """INSERT INTO stocks (symbol, name, market, exchange, sector, country, currency, is_active)
           VALUES (?, ?, 'KOSPI', 'KRX', 'IT', 'KR', 'KRW', true) RETURNING id""",
        Long::class.java, symbol, symbol,
    )!!

    private fun group(userId: Long): Long = jdbcTemplate.queryForObject(
        "INSERT INTO watchlist_groups (user_id, name) VALUES (?, ?) RETURNING id",
        Long::class.java, userId, "g-${System.nanoTime()}",
    )!!

    private fun watch(groupId: Long, stockId: Long) =
        jdbcTemplate.update("INSERT INTO watchlist_items (group_id, stock_id) VALUES (?, ?)", groupId, stockId)

    private fun token(userId: Long, token: String, active: Boolean = true) =
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, is_active) VALUES (?, ?, ?)", userId, token, active)

    private fun watchers(stockId: Long): List<Pair<Long, String>> =
        jdbcTemplate.query(StockEventWriter.WATCHER_DEVICE_TOKENS_SQL, { rs, _ -> rs.getLong("user_id") to rs.getString("token") }, stockId)

    @Test
    fun `returns active tokens of users who watch the stock, once even across several groups`() {
        val s = stock("WTK${System.nanoTime() % 100000}")
        val other = stock("OTH${System.nanoTime() % 100000}")
        val alice = user("alice")
        val bob = user("bob")
        val gone = user("gone", deleted = true)
        val stranger = user("stranger")

        group(alice).also { watch(it, s) }
        group(alice).also { watch(it, s) }          // 같은 종목을 두 그룹에
        group(bob).also { watch(it, s) }
        group(gone).also { watch(it, s) }
        group(stranger).also { watch(it, other) }   // 다른 종목만

        token(alice, "alice-1"); token(alice, "alice-2")
        token(bob, "bob-1"); token(bob, "bob-old", active = false)
        token(gone, "gone-1")
        token(stranger, "stranger-1")

        assertThat(watchers(s)).containsExactlyInAnyOrder(
            alice to "alice-1", alice to "alice-2", bob to "bob-1",
        )
    }
}
