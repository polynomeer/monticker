package com.monticker.api.common

import com.monticker.api.ai.OrderProposalUserDeletionListener
import com.monticker.api.analytics.application.AnalyticsUserDeletionListener
import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.domain.Price
import com.monticker.api.matching.application.LimitOrderFiller
import com.monticker.api.matching.application.MatchingOrderBookService
import com.monticker.api.matching.application.MatchingUserDeletionListener
import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderType
import com.monticker.api.paper.application.PaperUserDeletionListener
import com.monticker.api.quant.application.QuantUserDeletionListener
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.wallet.application.WalletUserDeletionListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

/**
 * ADR-109 Note — 탈퇴 이벤트 하나로 모의투자(wallet → paper → matching)·Quant·분석·AI 제안 데이터가 실제 Postgres의 FK를
 * 어기지 않고 지워지는지, 마켓 전략은 판매 중지만 되는지 본다. 리스너 순서는 Spring 이벤트 멀티캐스터가 `@Order`로 정한다 —
 * 그래서 컨텍스트를 띄워 실제로 발행한다(빈은 리스너와 JdbcTemplate만, Mongo 저장소는 목).
 */
class UserAccountDeletionIntegrationTest : PostgresIntegrationTest() {

    private fun token() = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun user(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "del-${token()}@test.local", "del",
    )!!

    private val stockId by lazy { jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id LIMIT 1", Long::class.java)!! }

    private fun id(sql: String, vararg args: Any?): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args)!!

    private fun count(table: String, where: String, vararg args: Any?): Long =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table WHERE $where", Long::class.java, *args)!!

    /** 한 사용자의 모의투자·퀀트 데이터 한 벌. 체결된 주문과 미체결 지정가를 하나씩 둔다. 돌려주는 값은 미체결 주문 id. */
    private fun seedPaper(userId: Long): Long {
        jdbcTemplate.update("INSERT INTO paper_accounts (user_id, cash) VALUES (?, 9000000)", userId)
        jdbcTemplate.update("INSERT INTO portfolio_positions (user_id, stock_id, net_qty, avg_buy_price, total_cost) VALUES (?, ?, 1, 1000, 1000)", userId, stockId)
        val filled = id(
            "INSERT INTO orders (user_id, stock_id, side, order_type, quantity, filled_qty, status) VALUES (?, ?, 'BUY', 'MARKET', 1, 1, 'FILLED') RETURNING id",
            userId, stockId,
        )
        val pending = id(
            "INSERT INTO orders (user_id, stock_id, side, order_type, quantity, limit_price, status) VALUES (?, ?, 'BUY', 'LIMIT', 1, 900, 'PENDING') RETURNING id",
            userId, stockId,
        )
        jdbcTemplate.update("INSERT INTO order_sagas (user_id, order_id, stock_id, side, quantity, status) VALUES (?, ?, ?, 'BUY', 1, 'COMPLETED')", userId, filled, stockId)
        val fill = id(
            "INSERT INTO fills (order_id, user_id, stock_id, side, quantity, fill_price, amount) VALUES (?, ?, ?, 'BUY', 1, 1000, 1000) RETURNING id",
            filled, userId, stockId,
        )
        val trade = id(
            "INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, fill_id) VALUES (?, ?, 'BUY', 1, 1000, 1000, ?) RETURNING id",
            userId, stockId, fill,
        )
        jdbcTemplate.update(
            """INSERT INTO paper_settlements (trade_id, user_id, stock_id, side, quantity, fill_price, gross_amount, net_amount, settle_date)
               VALUES (?, ?, ?, 'BUY', 1, 1000, 1000, 1000, CURRENT_DATE)""",
            trade, userId, stockId,
        )
        jdbcTemplate.update(
            """INSERT INTO paper_conditional_orders (user_id, stock_id, side, trigger_type, trigger_price, quantity, parent_order_id, status)
               VALUES (?, ?, 'SELL', 'STOP_LOSS', 800, 1, ?, 'WAITING_PARENT')""",
            userId, stockId, pending,
        )
        jdbcTemplate.update("INSERT INTO order_emotion_tags (paper_trade_id, user_id, emotion) VALUES (?, ?, 'CALM')", trade, userId)
        jdbcTemplate.update("INSERT INTO ledger_events (user_id, event_type, amount, paper_trade_id) VALUES (?, 'BUY', -1000, ?)", userId, trade)
        jdbcTemplate.update(
            "INSERT INTO ledger_snapshots (user_id, as_of_date, ledger_sum, account_cash, last_event_id) VALUES (?, CURRENT_DATE, 0, 0, 0)", userId,
        )
        jdbcTemplate.update("INSERT INTO investment_behavior_scores (user_id, score_date, behavior_score) VALUES (?, CURRENT_DATE, 50)", userId)
        jdbcTemplate.update(
            "INSERT INTO weekly_report_sends (user_id, week_start, status, claimed_at) VALUES (?, date_trunc('week', now())::date, 'SENT', now())", userId,
        )
        jdbcTemplate.update("INSERT INTO tax_harvesting_logs (user_id, realized_gain_ytd, candidates_json) VALUES (?, 0, '[]')", userId)
        jdbcTemplate.update("INSERT INTO portfolio_optimizations (user_id, universe_json, weights_json) VALUES (?, '[]', '{}')", userId)
        jdbcTemplate.update(
            "INSERT INTO order_proposals (user_id, stock_id, side, reasoning, expires_at) VALUES (?, ?, 'BUY', 'r', now() + interval '1 day')", userId, stockId,
        )
        return pending
    }

    private fun seedRuleSetOutputs(ruleSetId: String) {
        val ft = id(
            "INSERT INTO quant_forward_tests (rule_set_id, rule_set_version, stock_id, initial_capital, cash) VALUES (?, 1, ?, 1000, 1000) RETURNING id",
            ruleSetId, stockId,
        )
        jdbcTemplate.update("INSERT INTO quant_forward_test_equity (forward_test_id, eval_date, equity, drawdown) VALUES (?, CURRENT_DATE, 1000, 0)", ft)
        jdbcTemplate.update(
            "INSERT INTO quant_signals (rule_set_id, stock_id, direction, signal_time, forward_test_id, eval_date) VALUES (?, ?, 'BUY', now(), ?, CURRENT_DATE)",
            ruleSetId, stockId, ft,
        )
        jdbcTemplate.update(
            """INSERT INTO quant_backtest_results (rule_set_id, rule_set_version, stock_id, start_date, end_date, initial_capital, final_capital)
               VALUES (?, 1, ?, CURRENT_DATE, CURRENT_DATE, 1000, 1000)""",
            ruleSetId, stockId,
        )
    }

    private fun market(userId: Long, ruleSetId: String, subscribers: List<Long>): Long {
        val marketId = id(
            "INSERT INTO strategy_market (ruleset_id, user_id, subscribe_count) VALUES (?, ?, ?) RETURNING id",
            ruleSetId, userId, subscribers.size,
        )
        subscribers.forEach { jdbcTemplate.update("INSERT INTO strategy_subscriptions (market_id, user_id) VALUES (?, ?)", marketId, it) }
        return marketId
    }

    private fun ruleSetId() = token() + token()   // VARCHAR(24)

    private fun doc(id: String) = mockk<RuleSetDocument> { every { this@mockk.id } returns id }

    @Test
    fun `탈퇴 한 번으로 모의투자·퀀트 데이터가 FK 순서대로 지워지고 마켓 전략은 판매 중지만 된다`() {
        val deleted = user()
        val other = user()
        val subscriber = user()

        val pendingOrder = seedPaper(deleted)
        seedPaper(other)

        val myRuleSet = ruleSetId()
        val othersRuleSet = ruleSetId()
        seedRuleSetOutputs(myRuleSet)
        seedRuleSetOutputs(othersRuleSet)
        val myMarket = market(deleted, myRuleSet, subscribers = listOf(subscriber, other))
        val othersMarket = market(other, othersRuleSet, subscribers = listOf(deleted, subscriber))
        jdbcTemplate.update(
            """INSERT INTO creator_earnings (creator_id, strategy_id, subscriber_id, gross_amount, platform_fee, net_amount)
               VALUES (?, ?, ?, 1000, 100, 900)""",
            deleted, myMarket, subscriber,
        )

        val ruleSets = mockk<RuleSetRepository>(relaxed = true)
        every { ruleSets.findAllByUserId(deleted) } returns listOf(doc(myRuleSet))
        val orderBook = MatchingOrderBookService()
        orderBook.submit(Order(
            id = pendingOrder, userId = deleted, stockId = stockId, side = OrderSide.BUY, orderType = OrderType.LIMIT,
            quantity = 1, limitPrice = Price.of(BigDecimal("900")),
        ))

        val tx = DataSourceTransactionManager(dataSource)
        AnnotationConfigApplicationContext().use { ctx ->
            // 등록 순서를 FK 순서와 거꾸로 둔다 — 순서는 @Order가 정해야 한다.
            ctx.beanFactory.registerSingleton("jdbcTemplate", jdbcTemplate)
            ctx.beanFactory.registerSingleton("orderBook", orderBook)
            ctx.beanFactory.registerSingleton("ruleSetRepository", ruleSets)
            ctx.register(
                MatchingUserDeletionListener::class.java,
                PaperUserDeletionListener::class.java,
                WalletUserDeletionListener::class.java,
                QuantUserDeletionListener::class.java,
                AnalyticsUserDeletionListener::class.java,
                OrderProposalUserDeletionListener::class.java,
            )
            ctx.refresh()

            TransactionTemplate(tx).executeWithoutResult { ctx.publishEvent(UserAccountDeletedEvent(deleted)) }
        }

        listOf(
            "order_emotion_tags", "ledger_events", "ledger_snapshots", "investment_behavior_scores", "weekly_report_sends",
            "paper_settlements", "paper_trades", "paper_conditional_orders", "portfolio_positions", "paper_accounts",
            "fills", "order_sagas", "orders", "tax_harvesting_logs", "portfolio_optimizations", "order_proposals",
        ).forEach { table ->
            assertThat(count(table, "user_id = ?", deleted)).describedAs("$table of deleted user").isZero()
            assertThat(count(table, "user_id = ?", other)).describedAs("$table of other user").isPositive()
        }

        listOf("quant_signals", "quant_forward_tests", "quant_backtest_results").forEach { table ->
            assertThat(count(table, "rule_set_id = ?", myRuleSet)).describedAs(table).isZero()
            assertThat(count(table, "rule_set_id = ?", othersRuleSet)).describedAs(table).isPositive()
        }

        // 크리에이터로서: 행은 남고 판매 중지, 남의 구독은 끝난다. 정산 기록은 보존된다.
        assertThat(count("strategy_market", "id = ? AND delisted_at IS NOT NULL AND subscribe_count = 0", myMarket)).isOne()
        assertThat(count("strategy_subscriptions", "market_id = ?", myMarket)).isZero()
        assertThat(count("creator_earnings", "strategy_id = ?", myMarket)).isOne()
        // 구독자로서: 내 구독만 빠지고 수가 맞는다.
        assertThat(count("strategy_subscriptions", "market_id = ?", othersMarket)).isOne()
        assertThat(count("strategy_market", "id = ? AND delisted_at IS NULL AND subscribe_count = 1", othersMarket)).isOne()

        // 커밋 뒤: 호가창 사본과 Mongo 룰셋 문서
        assertThat(orderBook.getOrCreate(stockId).bids).isEmpty()
        verify(exactly = 1) { ruleSets.deleteAllById(listOf(myRuleSet)) }
    }

    @Test
    fun `freeze가 잡은 미체결 주문은 스위퍼가 건너뛴다`() {
        val userId = user()
        val pending = seedPaper(userId)

        dataSource.connection.use { deletion ->
            deletion.autoCommit = false
            deletion.prepareStatement(MatchingUserDeletionListener.LOCK_OPEN_ORDERS_SQL).use {
                it.setLong(1, userId)
                it.executeQuery().use { rs -> assertThat(rs.next()).isTrue() }
            }

            // 다른 연결(스위퍼)에서 같은 주문을 SKIP LOCKED로 잡으려 하면 못 잡는다 — 체결하지 않고 넘어간다.
            dataSource.connection.use { sweeper ->
                sweeper.autoCommit = false
                sweeper.prepareStatement(LimitOrderFiller.LOCK_SQL).use {
                    it.setLong(1, pending)
                    it.executeQuery().use { rs -> assertThat(rs.next()).isFalse() }
                }
                sweeper.rollback()
            }
            deletion.rollback()
        }
    }
}
