package com.monticker.api.wallet

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.common.notification.UnsubscribeScope
import com.monticker.api.common.notification.UnsubscribeTokenService
import com.monticker.api.common.time.KstPeriod
import com.monticker.api.paper.application.PaperRealizedPnlService
import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.wallet.application.EmotionTagService
import com.monticker.api.wallet.application.ScoreDetailService
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import com.monticker.api.wallet.report.WeeklyBehaviorReportJob
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.mail.SendFailedException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-101 — 주간 행동 리포트의 대상 선정 SQL, (사용자, 주) 한 번 보장, 재시도를 실제 Postgres에서 검증한다.
 * 다른 통합 테스트와 겹치지 않게 2031년 주를 쓰고, 결과는 이 테스트가 만든 사용자로만 확인한다.
 * 시각은 KST 벽시계로 적어 Instant로 바꾼다(`-Duser.timezone=UTC`로도 돌린다).
 */
class WeeklyBehaviorReportIntegrationTest : PostgresIntegrationTest() {

    private fun kst(s: String): Instant = LocalDateTime.parse(s).atZone(KstPeriod.KST).toInstant()

    /** 2031-03-10(월) 08:00 KST 실행 → 대상 주 3/3(월) ~ 3/9(일) */
    private val runAt = kst("2031-03-10T08:00:00")
    private val week = KstPeriod(LocalDate.of(2031, 3, 3), LocalDate.of(2031, 3, 9))

    private val stock: Long by lazy {
        jdbcTemplate.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
            Long::class.java, "WR${System.nanoTime() % 1_000_000}", "주간리포트",
        )!!
    }

    private fun user(verified: Boolean = true, deleted: Boolean = false, nickname: String = "wr"): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname, email_verified, deleted_at) VALUES (?, ?, ?, ?) RETURNING id",
        Long::class.java, "wr-${System.nanoTime()}@test.local", nickname, verified, if (deleted) Timestamp.from(runAt) else null,
    )!!

    private fun pref(user: Long, all: Boolean = true, email: Boolean = true, weekly: Boolean = true) {
        jdbcTemplate.update(
            "INSERT INTO notification_preferences (user_id, all_enabled, email_enabled, weekly_report_email) VALUES (?, ?, ?, ?)",
            user, all, email, weekly,
        )
    }

    private fun trade(user: Long, side: String, at: Instant, origin: String? = "MANUAL") {
        jdbcTemplate.update(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at, origin)
               VALUES (?, ?, ?, 1, 1000, 1000, ?, ?)""",
            user, stock, side, Timestamp.from(at), origin,
        )
    }

    private fun sendLog(user: Long): Map<String, Any?>? =
        jdbcTemplate.queryForList("SELECT * FROM weekly_report_sends WHERE user_id = ?", user).firstOrNull()

    /** 보낸 메일을 기록하는 SMTP 대역. [failFor]의 주소는 [failures]번 실패한다. */
    private class RecordingMailSender(private val delay: Duration = Duration.ZERO) : JavaMailSenderImpl() {
        val sent = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<MimeMessage>()
        val failFor = ConcurrentHashMap<String, Int>()
        var permanent = false

        override fun doSend(mimeMessages: Array<out MimeMessage>, originalMessages: Array<out Any>?) {
            for (m in mimeMessages) {
                val to = m.allRecipients.single().toString()
                if (!delay.isZero) Thread.sleep(delay.toMillis())
                val left = failFor[to]
                if (left != null && left > 0) {
                    failFor[to] = left - 1
                    if (permanent) {
                        throw MailSendException(mapOf<Any, Exception>(m to SendFailedException("550", null, emptyArray(), emptyArray(), arrayOf(InternetAddress(to)))))
                    }
                    throw MailSendException("421 try again later")
                }
                sent += to
                messages += m
            }
        }
    }

    private fun redisReturning(vararg legacy: Pair<Long, String>): StringRedisTemplate {
        val map = legacy.toMap()
        val ops = mockk<ValueOperations<String, String>>()
        every { ops.multiGet(any()) } answers {
            firstArg<Collection<String>>().map { k -> map[k.removePrefix("notif:pref:").toLong()] }
        }
        return mockk { every { opsForValue() } returns ops }
    }

    private fun job(
        mail: RecordingMailSender,
        redis: StringRedisTemplate = redisReturning(),
        at: Instant = runAt,
        pageSize: Int = 3,
    ) = WeeklyBehaviorReportJob(
        jdbc = jdbcTemplate,
        scoreDetails = ScoreDetailService(jdbcTemplate, PaperRealizedPnlService(jdbcTemplate), Clock.fixed(at, ZoneOffset.UTC)),
        emotions = EmotionTagService(mockk<EmotionTagRepository>(), mockk<PaperTradeQueryService>(), jdbcTemplate),
        mailSender = mail,
        redis = redis,
        objectMapper = ObjectMapper(),
        registry = SimpleMeterRegistry(),
        unsubscribeTokens = tokens,
        baseUrl = "https://app.test",
        pageSize = pageSize,
        maxAttempts = 3,
        retryBackoff = Duration.ofMinutes(30),
    ).apply { clock = Clock.fixed(at, ZoneOffset.UTC) }

    private val tokens = UnsubscribeTokenService("integration-test-unsubscribe-secret-0123456789")

    private fun emailOf(user: Long): String = jdbcTemplate.queryForList("SELECT email FROM users WHERE id = ?", String::class.java, user).single()

    @Test
    fun `only eligible users with trades in the KST week get exactly one report`() {
        val firstMoment = user().also { trade(it, "BUY", kst("2031-03-03T00:00:00")) }          // 주의 첫 순간 — 포함
        val lastMoment = user().also { pref(it); trade(it, "SELL", kst("2031-03-09T23:59:59")) } // 주의 마지막 순간 — 포함
        val nextWeekOnly = user().also { trade(it, "BUY", kst("2031-03-10T00:00:00")) }        // 다음 주 첫 순간 — 제외
        val prevWeekOnly = user().also { trade(it, "BUY", kst("2031-03-02T23:59:59")) }        // 전 주 — 제외
        val unverified = user(verified = false).also { trade(it, "BUY", kst("2031-03-04T10:00:00")) }
        val deleted = user(deleted = true).also { trade(it, "BUY", kst("2031-03-04T10:00:00")) }
        val weeklyOff = user().also { pref(it, weekly = false); trade(it, "BUY", kst("2031-03-04T10:00:00")) }
        val emailOff = user().also { pref(it, email = false); trade(it, "BUY", kst("2031-03-04T10:00:00")) }
        val allOff = user().also { pref(it, all = false); trade(it, "BUY", kst("2031-03-04T10:00:00")) }
        val legacyOff = user().also { trade(it, "BUY", kst("2031-03-04T10:00:00")) }            // 행 없음 + 옛 Redis로 끔
        val mine = listOf(firstMoment, lastMoment, nextWeekOnly, prevWeekOnly, unverified, deleted, weeklyOff, emailOff, allOff, legacyOff)

        val mail = RecordingMailSender()
        val redis = redisReturning(legacyOff to """{"weeklyReportEmail":false}""")
        job(mail, redis).run()

        val mineEmails = mine.associateBy { emailOf(it) }
        assertThat(mail.sent.mapNotNull { mineEmails[it] }).containsExactlyInAnyOrder(firstMoment, lastMoment)
        assertThat(sendLog(firstMoment)!!["status"]).isEqualTo("SENT")
        assertThat(sendLog(firstMoment)!!["week_start"].toString()).isEqualTo("2031-03-03")
        assertThat(sendLog(lastMoment)!!["status"]).isEqualTo("SENT")
        (mine - firstMoment - lastMoment).forEach { assertThat(sendLog(it)).describedAs("user $it").isNull() }

        // 재시작·다음 실행(30분 뒤)에도 다시 보내지 않는다
        val again = RecordingMailSender()
        job(again, redis, at = runAt.plus(Duration.ofMinutes(30))).run()
        assertThat(again.sent.mapNotNull { mineEmails[it] }).isEmpty()
    }

    @Test
    fun `concurrent runs on several pods send each user exactly once`() {
        val users = (1..25).map { user().also { u -> trade(u, "BUY", kst("2031-03-05T10:00:00")) } }
        val emails = users.associateBy { emailOf(it) }
        val mail = RecordingMailSender(delay = Duration.ofMillis(5)) // 같은 SMTP를 공유하는 4개 "파드"
        val pods = (1..4).map { job(mail, pageSize = 4) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(pods.size)
        val results = pods.map { p -> pool.submit<WeeklyBehaviorReportJob.RunResult> { start.await(); p.run() } }
        start.countDown()
        results.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        val mine = mail.sent.mapNotNull { emails[it] }
        assertThat(mine).hasSize(users.size).doesNotHaveDuplicates()
        users.forEach { assertThat(sendLog(it)!!["status"]).isEqualTo("SENT"); assertThat(sendLog(it)!!["attempts"]).isEqualTo(1) }
    }

    @Test
    fun `transient smtp failure is retried after the backoff without duplicates, permanent failure is not`() {
        val flaky = user().also { trade(it, "BUY", kst("2031-03-05T10:00:00")) }
        val mail = RecordingMailSender()
        mail.failFor[emailOf(flaky)] = 1

        job(mail).run()
        assertThat(sendLog(flaky)!!["status"]).isEqualTo("FAILED")
        assertThat(sendLog(flaky)!!["attempts"]).isEqualTo(1)
        assertThat(sendLog(flaky)!!["last_error"]).isEqualTo("MailSendException") // 주소 등 메시지는 남기지 않는다
        assertThat(mail.sent).doesNotContain(emailOf(flaky))

        // 재시도 시각 전 실행은 건드리지 않는다
        job(mail, at = runAt.plus(Duration.ofMinutes(10))).run()
        assertThat(sendLog(flaky)!!["attempts"]).isEqualTo(1)

        // 30분 뒤 — 다시 선점해 보낸다(시도 2)
        job(mail, at = runAt.plus(Duration.ofMinutes(31))).run()
        assertThat(sendLog(flaky)!!["status"]).isEqualTo("SENT")
        assertThat(sendLog(flaky)!!["attempts"]).isEqualTo(2)
        assertThat(mail.sent.count { it == emailOf(flaky) }).isEqualTo(1)

        // 영구 실패(수신 주소 거부)는 다시 시도하지 않는다
        val bounced = user().also { trade(it, "BUY", kst("2031-03-05T10:00:00")) }
        val bounceMail = RecordingMailSender().apply { permanent = true; failFor[emailOf(bounced)] = 5 }
        job(bounceMail).run()
        assertThat(sendLog(bounced)!!["status"]).isEqualTo("FAILED")
        assertThat(sendLog(bounced)!!["next_attempt_at"]).isNull()
        job(bounceMail, at = runAt.plus(Duration.ofHours(5))).run()
        assertThat(sendLog(bounced)!!["attempts"]).isEqualTo(1)
        assertThat(bounceMail.sent).doesNotContain(emailOf(bounced))
    }

    @Test
    fun `sent mail carries RFC 8058 one-click unsubscribe headers with a token for that user`() {
        val u = user().also { trade(it, "BUY", kst("2031-03-05T10:00:00")) }
        val mail = RecordingMailSender()
        job(mail).run()

        val msg = mail.messages.single { it.allRecipients.single().toString() == emailOf(u) }
        val listUnsubscribe = msg.getHeader("List-Unsubscribe").single()
        assertThat(msg.getHeader("List-Unsubscribe-Post").single()).isEqualTo("List-Unsubscribe=One-Click")
        val m = Regex("""^<https://app\.test/api/unsubscribe\?token=([^>]+)>$""").matchEntire(listUnsubscribe)
        assertThat(m).describedAs(listUnsubscribe).isNotNull
        val token = m!!.groupValues[1]
        assertThat(tokens.verify(token, UnsubscribeScope.WEEKLY_REPORT)).isEqualTo(u)
        // 토큰에 이메일 주소가 드러나지 않는다
        assertThat(token).doesNotContain(emailOf(u)).doesNotContain("@")
        // HTML 본문은 웹 확인 화면으로 보낸다(GET으로는 끄지 않는다)
        msg.saveChanges() // 실제 발송처럼 본문 파트의 Content-Type 헤더를 채운다
        assertThat(htmlOf(msg)).contains("https://app.test/unsubscribe?token=$token")
    }

    private fun htmlOf(part: jakarta.mail.Part): String {
        val content = part.content
        if (part.isMimeType("text/html")) return content as String
        if (content is jakarta.mail.Multipart) {
            return (0 until content.count).joinToString("") { htmlOf(content.getBodyPart(it)) }
        }
        return ""
    }

    @Test
    fun `a claim left in SENDING by a crashed pod is never resent`() {
        val u = user().also { trade(it, "BUY", kst("2031-03-05T10:00:00")) }
        jdbcTemplate.update(
            "INSERT INTO weekly_report_sends (user_id, week_start, status, attempts, claimed_at) VALUES (?, ?, 'SENDING', 1, ?)",
            u, java.sql.Date.valueOf(week.from), Timestamp.from(runAt.minus(Duration.ofHours(3))),
        )
        val mail = RecordingMailSender()
        job(mail, at = runAt.plus(Duration.ofHours(6))).run()
        assertThat(mail.sent).doesNotContain(emailOf(u))
        assertThat(sendLog(u)!!["status"]).isEqualTo("SENDING")
    }

    @Test
    fun `report content uses the ADR-091 definitions for the finished week`() {
        val u = user()
        trade(u, "BUY", kst("2031-03-04T10:00:00"), origin = "WATCH_RULE")   // 계획
        trade(u, "BUY", kst("2031-03-05T10:00:00"), origin = "MANUAL")       // 계획 아님
        trade(u, "BUY", kst("2031-02-25T10:00:00"), origin = "MANUAL")       // 전 주
        jdbcTemplate.update(
            "INSERT INTO investment_behavior_scores (user_id, score_date, behavior_score) VALUES (?, ?, 60), (?, ?, 80)",
            u, LocalDate.of(2031, 2, 27), u, LocalDate.of(2031, 3, 6),
        )
        val report = job(RecordingMailSender()).build(u, week)!!
        assertThat(report.buyCount).isEqualTo(2)
        assertThat(report.sellCount).isEqualTo(0)
        assertThat(report.previousTradeCount).isEqualTo(1)
        assertThat(report.planAdherence.thisWeek.pct).isEqualTo(50.0)
        assertThat(report.planAdherence.deltaPp).isEqualTo(50.0)                // 전 주 0/1
        assertThat(report.behaviorScore.thisWeekAvg).isEqualTo(80.0)
        assertThat(report.behaviorScore.delta).isEqualTo(20.0)
    }
}
