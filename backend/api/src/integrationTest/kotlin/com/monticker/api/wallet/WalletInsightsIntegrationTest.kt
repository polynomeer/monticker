package com.monticker.api.wallet

import com.monticker.api.common.time.KstPeriod
import com.monticker.api.paper.application.PaperRealizedPnlService
import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.wallet.application.DailyReturnService
import com.monticker.api.wallet.application.DailyReturnStatus
import com.monticker.api.wallet.application.EmotionTagService
import com.monticker.api.wallet.application.Ratio
import com.monticker.api.wallet.application.ScoreDetailService
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * ADR-091 — /wallet 점수 카드 세부 지표, 감정 분포 기간, 날짜별 수익률의 SQL을 실제 Postgres에서 검증한다.
 * 시각은 모두 KST 벽시계로 적고 Instant로 바꿔 넣는다. JVM 시간대와 무관해야 한다(`-Duser.timezone=UTC`로도 돌린다).
 */
class WalletInsightsIntegrationTest : PostgresIntegrationTest() {

    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(KstPeriod.KST).toInstant()
    private fun ts(i: Instant) = Timestamp.from(i)

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "wi-${System.nanoTime()}@test.local", "wi",
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "WI${System.nanoTime() % 1_000_000}", "지갑지표",
    )!!

    private fun trade(user: Long, stock: Long, side: String, qty: Int, price: String, at: Instant, origin: String? = "MANUAL", ref: Long? = null): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at, origin, origin_ref)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
            Long::class.java, user, stock, side, qty, BigDecimal(price), BigDecimal(price).multiply(BigDecimal(qty)), ts(at), origin, ref,
        )!!

    private fun tag(tradeId: Long, user: Long, emotion: String) {
        jdbcTemplate.update("INSERT INTO order_emotion_tags (paper_trade_id, user_id, emotion) VALUES (?, ?, ?)", tradeId, user, emotion)
    }

    private fun stopLoss(user: Long, stock: Long, price: String, createdAt: Instant, status: String = "ACTIVE"): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO paper_conditional_orders (user_id, stock_id, side, trigger_type, trigger_price, quantity, status, created_at)
               VALUES (?, ?, 'SELL', 'STOP_LOSS', ?, 1, ?, ?) RETURNING id""",
            Long::class.java, user, stock, BigDecimal(price), status, ts(createdAt),
        )!!

    private fun candle(stock: Long, close: String, at: Instant) {
        val c = BigDecimal(close)
        jdbcTemplate.update(
            "INSERT INTO candles_1m (stock_id, open, high, low, close, candle_time) VALUES (?, ?, ?, ?, ?, ?)",
            stock, c, c, c, c, ts(at),
        )
    }

    private fun ledger(user: Long, type: String, amount: String, at: Instant, dedup: String? = null) {
        jdbcTemplate.update(
            "INSERT INTO ledger_events (user_id, event_type, amount, created_at, dedup_key) VALUES (?, ?, ?, ?, ?)",
            user, type, BigDecimal(amount), ts(at), dedup,
        )
    }

    private fun account(user: Long, createdAt: Instant) {
        jdbcTemplate.update(
            "INSERT INTO paper_accounts (user_id, cash, initial_capital, created_at, updated_at) VALUES (?, 10000000, 10000000, ?, ?)",
            user, ts(createdAt), ts(createdAt),
        )
    }

    // ── 점수 카드 세부 지표 ──

    @Test
    fun `score details split weeks at Monday 00_00 KST and compute plan and stop-loss adherence`() {
        val me = newUser()
        val other = newUser()
        val s1 = stock(); val s2 = stock(); val s3 = stock(); val s4 = stock()
        val service = ScoreDetailService(jdbcTemplate, PaperRealizedPnlService(jdbcTemplate), Clock.fixed(kst("2026-10-12T10:00:00"), ZoneOffset.UTC))

        // 지난주 마지막 순간(일 23:59:59) — Watch Rule 주문 = 계획
        trade(me, s1, "BUY", 1, "1000", kst("2026-10-11T23:59:59"), "WATCH_RULE", 9)
        // 이번 주 첫 순간(월 00:00:00) — 직접 주문, 다른 사용자가 남긴 PLANNED 태그(예전 IDOR 잔여)는 무시
        val unplanned = trade(me, s1, "BUY", 1, "1000", kst("2026-10-12T00:00:00"))
        tag(unplanned, other, "PLANNED")
        // 이번 주 — 직접 주문 + 본인 PLANNED 태그 = 계획
        tag(trade(me, s1, "BUY", 1, "1000", kst("2026-10-12T09:01:00")), me, "PLANNED")

        // 손절: 지난달에 연 포지션들, 이번 주 손실 매도
        trade(me, s2, "BUY", 10, "1000", kst("2026-09-28T10:00:00"), null)
        stopLoss(me, s2, "950", kst("2026-09-28T10:00:01"), status = "CANCELLED") // 정했다가 지움 — 그래도 기준
        trade(me, s2, "SELL", 10, "900", kst("2026-10-12T09:30:00"), null)         // 손절가 아래 → 못 지킴
        trade(me, s3, "BUY", 10, "1000", kst("2026-09-28T10:00:00"), null)
        stopLoss(me, s3, "950", kst("2026-09-28T10:00:01"))
        trade(me, s3, "SELL", 10, "960", kst("2026-10-12T09:31:00"), null)         // 손절가 위에서 정리 → 지킴
        trade(me, s4, "BUY", 10, "1000", kst("2026-09-28T10:00:00"), null)
        trade(me, s4, "SELL", 10, "900", kst("2026-10-12T09:32:00"), null)         // 손절 없음 → 제외
        stopLoss(other, s4, "990", kst("2026-09-28T10:00:01"))                     // 남의 손절은 내 손절이 아니다

        jdbcTemplate.update("INSERT INTO investment_behavior_scores (user_id, score_date, behavior_score) VALUES (?, ?, 70), (?, ?, 80), (?, ?, 90)",
            me, LocalDate.of(2026, 10, 6), me, LocalDate.of(2026, 10, 11), me, LocalDate.of(2026, 10, 12))

        val d = service.weekly(me)

        assertThat(d.weekStart).isEqualTo(LocalDate.of(2026, 10, 12))
        assertThat(d.lastWeekStart).isEqualTo(LocalDate.of(2026, 10, 5))
        // 이번 주: 직접(태그 없음) ✗, PLANNED ✓ — 매도 3건은 출처 NULL이라 판정 불가
        assertThat(d.planAdherence.thisWeek).isEqualTo(Ratio(1, 2))
        assertThat(d.planAdherence.lastWeek).isEqualTo(Ratio(1, 1))
        assertThat(d.planAdherence.deltaPp).isEqualTo(-50.0)
        assertThat(d.stopLossAdherence.thisWeek).isEqualTo(Ratio(1, 2))
        assertThat(d.stopLossAdherence.lastWeek).isEqualTo(Ratio(0, 0))
        assertThat(d.stopLossAdherence.lastWeek.pct).isNull()
        assertThat(d.stopLossAdherence.deltaPp).isNull()
        assertThat(d.behaviorScore.thisWeekAvg).isEqualTo(90.0)
        assertThat(d.behaviorScore.lastWeekAvg).isEqualTo(75.0)
        assertThat(d.behaviorScore.delta).isEqualTo(15.0)
    }

    // ── 감정 분포 기간 ──

    @Test
    fun `emotion analysis filters by trade time in the KST period and keeps the next-sell return`() {
        val me = newUser()
        val other = newUser()
        val s = stock()
        val service = EmotionTagService(mockk<EmotionTagRepository>(), mockk<PaperTradeQueryService>(), jdbcTemplate)

        tag(trade(me, s, "BUY", 1, "1000", kst("2026-10-05T00:00:00")), me, "FOMO")        // 구간 첫 순간 — 포함
        trade(me, s, "SELL", 1, "1100", kst("2026-10-06T10:00:00"))                        // 위 매수 뒤 첫 매도 +10%
        tag(trade(me, s, "BUY", 1, "1000", kst("2026-10-04T23:59:59")), me, "CONFIDENT")   // 구간 밖
        tag(trade(me, s, "SELL", 1, "1000", kst("2026-10-11T23:59:59")), me, "PLANNED")    // 구간 마지막 순간 — 포함
        tag(trade(other, s, "BUY", 1, "1000", kst("2026-10-06T10:00:00")), other, "FOMO")  // 다른 사용자

        val week = service.getAnalysis(me, KstPeriod(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)))
        assertThat(week.totalCount).isEqualTo(2)
        assertThat(week.stats.map { it.emotion }).containsExactlyInAnyOrder("FOMO", "PLANNED")
        assertThat(week.stats.first { it.emotion == "FOMO" }.avgReturnPct!!).isCloseTo(10.0, within(1e-9))
        assertThat(week.stats.first { it.emotion == "FOMO" }.sharePct).isEqualTo(50.0)

        val all = service.getAnalysis(me)
        assertThat(all.totalCount).isEqualTo(3)
        assertThat(all.from).isNull()
    }

    // ── 날짜별 수익률 ──

    @Test
    fun `daily returns use cash plus holdings at KST midnights and take deposits out of pnl`() {
        val me = newUser()
        val s = stock()
        account(me, kst("2026-09-01T09:00:00"))
        // 10/6 10:00 100주 @10,000 매수 — 원장 FILL −100만
        trade(me, s, "BUY", 100, "10000", kst("2026-10-06T10:00:00"))
        ledger(me, "FILL", "-1000000", kst("2026-10-06T10:00:00"))
        // 10/7 11:00 입금 50만(손익 아님)
        ledger(me, "DEPOSIT", "500000", kst("2026-10-07T11:00:00"))
        // 현금과 무관한 원장(구독 결제)은 빠진다
        ledger(me, "SUBSCRIPTION_PAYMENT", "-9900", kst("2026-10-07T12:00:00"))
        candle(s, "10000", kst("2026-10-06T09:59:00"))
        candle(s, "10500", kst("2026-10-06T15:20:00"))
        candle(s, "10200", kst("2026-10-07T15:20:00"))
        candle(s, "10300", kst("2026-10-08T11:00:00"))
        // 다른 사용자 데이터는 섞이지 않는다
        val other = newUser()
        account(other, kst("2026-09-01T09:00:00"))
        ledger(other, "DEPOSIT", "99999999", kst("2026-10-06T10:00:00"))

        val service = DailyReturnService(jdbcTemplate, Clock.fixed(kst("2026-10-08T12:00:00"), ZoneOffset.UTC))
        val r = service.daily(me, KstPeriod(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9))).days

        assertThat(r.map { it.date }).containsExactly( // 내일(10/9)은 없다
            LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8),
        )
        assertThat(r.map { it.status }).containsOnly(DailyReturnStatus.OK)
        assertThat(r[0].returnPct!!).isCloseTo(0.0, within(1e-9))
        assertThat(r[1].startEquity).isEqualByComparingTo("10000000")
        assertThat(r[1].endEquity).isEqualByComparingTo("10050000")        // 900만 + 100 × 10,500
        assertThat(r[1].returnPct!!).isCloseTo(0.5, within(1e-9))
        assertThat(r[2].netFlow).isEqualByComparingTo("500000")
        assertThat(r[2].pnl).isEqualByComparingTo("-30000")                // 1,052만 − 1,005만 − 50만
        assertThat(r[2].returnPct!!).isCloseTo(-30000.0 / 10050000 * 100, within(1e-9))
        assertThat(r[3].endEquity).isEqualByComparingTo("10530000")         // 오늘은 지금까지(최신 종가)
        assertThat(r[3].returnPct!!).isCloseTo(10000.0 / 10520000 * 100, within(1e-9))
    }

    @Test
    fun `days up to the latest reset and days without an account are not computed`() {
        val me = newUser()
        account(me, kst("2026-10-06T09:00:00"))
        ledger(me, "WITHDRAWAL", "-500000", kst("2026-10-07T13:00:00"), dedup = "RESET:${System.nanoTime()}")
        val service = DailyReturnService(jdbcTemplate, Clock.fixed(kst("2026-10-08T12:00:00"), ZoneOffset.UTC))

        val r = service.daily(me, KstPeriod(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 8))).days
        assertThat(r.map { it.status }).containsExactly(
            DailyReturnStatus.NO_ACCOUNT, DailyReturnStatus.RESET, DailyReturnStatus.RESET, DailyReturnStatus.OK,
        )

        val none = service.daily(newUser(), KstPeriod(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8))).days
        assertThat(none.map { it.status }).containsOnly(DailyReturnStatus.NO_ACCOUNT)
    }
}
