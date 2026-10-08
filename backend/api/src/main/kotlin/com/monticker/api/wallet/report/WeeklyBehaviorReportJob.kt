package com.monticker.api.wallet.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.common.time.KstPeriod
import com.monticker.api.wallet.application.EmotionTagService
import com.monticker.api.wallet.application.ScoreDetailService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import jakarta.mail.SendFailedException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.MailException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailPreparationException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-101 — 주간 투자 행동 리포트(매주 월요일 · 이메일).
 *
 * - **대상**: 탈퇴하지 않았고 이메일을 인증했고, 전체 알림·이메일 채널·주간 리포트가 켜져 있고([ReportPreference]),
 *   지난주(월~일 KST) 모의 체결이 있는 사용자. 방해 금지 시간은 보지 않는다(이메일은 대상이 아니다 — ADR-093).
 * - **한 번 보장**: (user_id, week_start) 행을 먼저 선점(INSERT … ON CONFLICT)한 인스턴스만 보낸다. 여러 파드·재시작에도
 *   같은 주는 한 번이다(ADR-070 risk_limit_warnings와 같은 방식). 선점 뒤 죽어 SENDING으로 남은 행은 다시 보내지 않는다 —
 *   메일이 나갔는지 알 수 없어서 중복보다 누락을 택한다.
 * - **재시도**: SMTP가 거부했거나 연결이 안 된 경우(보내지 않았음이 확실)만 FAILED로 두고 [retryBackoff] × 시도 횟수 뒤
 *   [maxAttempts]까지 다시 선점한다. 주소 자체가 거부되면(영구 실패) 다시 시도하지 않는다.
 * - **범위**: 사용자 id 키셋 페이지([pageSize])로 후보를 한 쿼리씩 읽는다. 한 번 실행은 [maxPerRun]명·[timeBudget]까지만 —
 *   남은 사용자는 다음 실행(30분 뒤)이 이어 받는다. 지표 계산은 사용자마다 ADR-091 서비스(점수 카드·감정 분포와 같은 함수)를 부른다.
 * - 로그·sent-log에 이메일 주소나 리포트 내용을 남기지 않는다(userId만).
 */
@Component
class WeeklyBehaviorReportJob(
    private val jdbc: JdbcTemplate,
    private val scoreDetails: ScoreDetailService,
    private val emotions: EmotionTagService,
    private val mailSender: JavaMailSender,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val registry: MeterRegistry,
    @Value("\${app.weekly-report.enabled:true}") private val enabled: Boolean = true,
    @Value("\${app.mail.from:noreply@monticker.io}") private val from: String = "noreply@monticker.io",
    @Value("\${app.base-url:http://localhost:3000}") private val baseUrl: String = "http://localhost:3000",
    @Value("\${app.weekly-report.page-size:200}") private val pageSize: Int = 200,
    @Value("\${app.weekly-report.max-per-run:2000}") private val maxPerRun: Int = 2000,
    @Value("\${app.weekly-report.time-budget:PT20M}") private val timeBudget: Duration = Duration.ofMinutes(20),
    @Value("\${app.weekly-report.max-attempts:3}") private val maxAttempts: Int = 3,
    @Value("\${app.weekly-report.retry-backoff:PT30M}") private val retryBackoff: Duration = Duration.ofMinutes(30),
) {
    /** 테스트에서 시각을 고정할 때만 바꾼다. */
    var clock: Clock = Clock.systemUTC()
    private val log = LoggerFactory.getLogger(javaClass)
    private val stopping = AtomicBoolean(false)

    enum class Outcome { SENT, SKIPPED, FAILED, NOT_CLAIMED }

    data class RunResult(val week: KstPeriod, val sent: Int, val skipped: Int, val failed: Int, val notClaimed: Int, val truncated: Boolean)

    internal data class Candidate(val userId: Long, val email: String, val nickname: String, val hasPrefRow: Boolean)

    /**
     * 월요일 08:00~20:30 KST, 30분마다. 첫 실행이 대부분을 보내고, 뒤 실행들은 [maxPerRun]을 넘긴 나머지·재시도·
     * 08:00에 떠 있지 않던 파드의 몫을 이어 받는다(보낸 사용자는 후보 쿼리에서 빠지므로 빈 실행은 쿼리 한 번이다).
     * 스케줄러 스레드(기본 1개, 다른 @Scheduled와 공유)를 붙잡지 않도록 전용 실행기에서 돈다 — 이 파드에서 이미 돌고
     * 있으면 이번 트리거는 버린다(AsyncConfig.weeklyReportExecutor).
     */
    @Async("weeklyReportExecutor")
    @Scheduled(cron = "\${app.weekly-report.cron:0 0/30 8-20 * * MON}", zone = "Asia/Seoul")
    fun scheduled() {
        if (!enabled) return
        try {
            run()
        } catch (e: Exception) {
            log.error("[WeeklyReport] 실행 실패: {}", e.javaClass.simpleName, e)
        }
    }

    @PreDestroy
    fun stop() = stopping.set(true)

    fun run(): RunResult {
        val now = clock.instant()
        val week = ReportWeek.at(now)
        val startedNanos = System.nanoTime()
        val budgetNanos = timeBudget.toNanos()
        purge(week)

        val counts = IntArray(Outcome.entries.size)
        var processed = 0
        var truncated = false
        var after = 0L
        loop@ while (true) {
            val page = candidates(week, after, clock.instant())
            if (page.isEmpty()) break
            val prefs = legacyPreferences(page.filter { !it.hasPrefRow }.map { it.userId })
            for (c in page) {
                after = c.userId
                if (processed >= maxPerRun || System.nanoTime() - startedNanos > budgetNanos || stopping.get()) {
                    truncated = true
                    break@loop
                }
                if (!c.hasPrefRow) {
                    val pref = prefs[c.userId]
                    if (pref == null) {
                        // 옛 설정을 읽지 못했다(Redis 장애) — 끈 사용자일 수 있으니 보내지 않고 다음 실행에 다시 본다(기록 없음)
                        skip("pref_unavailable"); counts[Outcome.SKIPPED.ordinal]++; continue
                    }
                    // 옛 설정으로 끈 사용자 — 후보가 아니다(지표에 세지 않는다)
                    if (!pref.wantsReport) continue
                }
                processed++
                counts[process(c, week).ordinal]++
            }
            if (page.size < pageSize) break
        }
        val result = RunResult(
            week, counts[Outcome.SENT.ordinal], counts[Outcome.SKIPPED.ordinal], counts[Outcome.FAILED.ordinal],
            counts[Outcome.NOT_CLAIMED.ordinal], truncated,
        )
        registry.timer("weekly_behavior_report_run").record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
        if (result.sent + result.skipped + result.failed + result.notClaimed > 0 || truncated) {
            log.info(
                "[WeeklyReport] week={} sent={} skipped={} failed={} notClaimed={} truncated={}",
                week.from, result.sent, result.skipped, result.failed, result.notClaimed, truncated,
            )
        }
        return result
    }

    /**
     * 후보 한 페이지(사용자 id 키셋). 설정 행이 없는 사용자는 기본값(켜짐)으로 일단 넣고, 옛 Redis 설정은 [legacyPreferences]가
     * 페이지 단위로 한 번에 확인한다. 이미 보냈거나·보내는 중·건너뛴 주, 재시도 시각 전이거나 횟수를 다 쓴 FAILED는 뺀다.
     */
    internal fun candidates(week: KstPeriod, afterUserId: Long, now: Instant): List<Candidate> = jdbc.query(
        """SELECT u.id, u.email, u.nickname, (np.user_id IS NOT NULL) AS has_pref
           FROM users u
           LEFT JOIN notification_preferences np ON np.user_id = u.id
           LEFT JOIN weekly_report_sends s ON s.user_id = u.id AND s.week_start = ?
           WHERE u.id > ? AND u.deleted_at IS NULL AND u.email_verified
             AND (np.user_id IS NULL OR (np.all_enabled AND np.email_enabled AND np.weekly_report_email))
             AND (s.user_id IS NULL OR (s.status = 'FAILED' AND s.attempts < ? AND s.next_attempt_at <= ?))
             AND EXISTS (SELECT 1 FROM paper_trades t WHERE t.user_id = u.id AND t.traded_at >= ? AND t.traded_at < ?)
           ORDER BY u.id
           LIMIT ?""",
        { rs, _ -> Candidate(rs.getLong("id"), rs.getString("email"), rs.getString("nickname"), rs.getBoolean("has_pref")) },
        Date.valueOf(week.from), afterUserId, maxAttempts, Timestamp.from(now),
        Timestamp.from(week.start), Timestamp.from(week.endExclusive), pageSize,
    )

    /**
     * 설정 행이 없는 사용자의 옛 Redis 설정(ADR-082 지연 이전, `notif:pref:{userId}` — NotificationPreferenceService.legacyKey와 같다).
     * 키가 없으면 기본값, 해석이 안 되면 기본값(설정 화면과 같다). Redis 자체가 실패하면 그 페이지의 행 없는 사용자는 결과에서 빠진다.
     */
    internal fun legacyPreferences(userIds: List<Long>): Map<Long, ReportPreference> {
        if (userIds.isEmpty()) return emptyMap()
        val values = try {
            redis.opsForValue().multiGet(userIds.map { "notif:pref:$it" }) ?: return emptyMap()
        } catch (e: Exception) {
            log.warn("[WeeklyReport] 옛 알림 설정 조회 실패 — 이번 실행에서 {}명 보류: {}", userIds.size, e.javaClass.simpleName)
            return emptyMap()
        }
        return userIds.zip(values).associate { (id, json) -> id to parseLegacy(json) }
    }

    private fun parseLegacy(json: String?): ReportPreference {
        if (json == null) return ReportPreference()
        return runCatching {
            val n = objectMapper.readTree(json)
            fun flag(name: String) = n.get(name)?.takeIf { it.isBoolean }?.booleanValue() ?: true
            ReportPreference(flag("allEnabled"), flag("emailEnabled"), flag("weeklyReportEmail"))
        }.getOrElse { ReportPreference() }
    }

    internal fun process(c: Candidate, week: KstPeriod): Outcome {
        val attempt = claim(c.userId, week) ?: run {
            registry.counter("weekly_behavior_report_total", "outcome", "not_claimed").increment()
            return Outcome.NOT_CLAIMED
        }
        val email = try {
            val report = build(c.userId, week)
            if (report == null) {
                finish(c.userId, week, attempt, "SKIPPED", null, null)
                skip("no_trades")
                return Outcome.SKIPPED
            }
            WeeklyBehaviorReportRenderer.render(report, c.nickname, baseUrl)
        } catch (e: Exception) {
            // 보내기 전 실패(DB 등) — 보내지 않았음이 확실하다
            return failed(c.userId, week, attempt, e, retry = true)
        }
        try {
            send(c.email, email)
        } catch (e: MailException) {
            return failed(c.userId, week, attempt, e, retry = isTransient(e))
        }
        finish(c.userId, week, attempt, "SENT", null, null)
        registry.counter("weekly_behavior_report_total", "outcome", "sent").increment()
        return Outcome.SENT
    }

    /** @return 이번 선점의 시도 번호. 다른 인스턴스가 이미 가졌거나 보냈으면 null. */
    internal fun claim(userId: Long, week: KstPeriod): Int? {
        val now = Timestamp.from(clock.instant())
        return jdbc.query(
            """INSERT INTO weekly_report_sends AS w (user_id, week_start, status, attempts, claimed_at)
               VALUES (?, ?, 'SENDING', 1, ?)
               ON CONFLICT (user_id, week_start) DO UPDATE
                   SET status = 'SENDING', attempts = w.attempts + 1, claimed_at = EXCLUDED.claimed_at, next_attempt_at = NULL
                   WHERE w.status = 'FAILED' AND w.attempts < ? AND w.next_attempt_at <= ?
               RETURNING attempts""",
            { rs, _ -> rs.getInt(1) },
            userId, Date.valueOf(week.from), now, maxAttempts, now,
        ).firstOrNull()
    }

    /** 리포트 내용. 그 주 체결이 없으면(선점 사이에 초기화 등) null. */
    fun build(userId: Long, week: KstPeriod): WeeklyBehaviorReport? {
        val previous = KstPeriod.weekOf(week.from.minusDays(1))
        val counts = jdbc.query(
            """SELECT COUNT(*) FILTER (WHERE traded_at >= ? AND side = 'BUY')  AS buys,
                      COUNT(*) FILTER (WHERE traded_at >= ? AND side = 'SELL') AS sells,
                      COUNT(*) FILTER (WHERE traded_at < ?)                    AS previous
               FROM paper_trades WHERE user_id = ? AND traded_at >= ? AND traded_at < ?""",
            { rs, _ -> Triple(rs.getInt("buys"), rs.getInt("sells"), rs.getInt("previous")) },
            Timestamp.from(week.start), Timestamp.from(week.start), Timestamp.from(week.start),
            userId, Timestamp.from(previous.start), Timestamp.from(week.endExclusive),
        ).firstOrNull() ?: return null
        if (counts.first + counts.second == 0) return null
        return WeeklyBehaviorReport.of(
            week, counts.first, counts.second, counts.third,
            scoreDetails.forWeek(userId, week),
            emotions.getAnalysis(userId, week),
        )
    }

    private fun send(to: String, email: RenderedEmail) {
        val msg = mailSender.createMimeMessage()
        MimeMessageHelper(msg, true, "UTF-8").apply {
            setFrom(from)
            setTo(to)
            setSubject(email.subject)
            setText(email.text, email.html)
        }
        // 메일 클라이언트의 "수신 거부" 버튼 — 설정 화면(로그인 후 토글)으로 보낸다
        msg.setHeader("List-Unsubscribe", "<${WeeklyBehaviorReportRenderer.settingsUrl(baseUrl)}>")
        mailSender.send(msg)
    }

    private fun failed(userId: Long, week: KstPeriod, attempt: Int, e: Exception, retry: Boolean): Outcome {
        val next = if (retry && attempt < maxAttempts) clock.instant().plus(retryBackoff.multipliedBy(attempt.toLong())) else null
        // 예외 메시지에는 수신 주소가 들어 있을 수 있어 클래스 이름만 남긴다
        finish(userId, week, attempt, "FAILED", next, e.javaClass.simpleName)
        registry.counter("weekly_behavior_report_total", "outcome", "failed").increment()
        log.warn("[WeeklyReport] 발송 실패 userId={} week={} attempt={} retry={}: {}", userId, week.from, attempt, next != null, e.javaClass.simpleName)
        return Outcome.FAILED
    }

    /** 선점한 그 시도(attempts)일 때만 바꾼다 — 다른 시도의 결과를 덮지 않는다. */
    private fun finish(userId: Long, week: KstPeriod, attempt: Int, status: String, nextAttemptAt: Instant?, error: String?) {
        jdbc.update(
            """UPDATE weekly_report_sends SET status = ?, sent_at = ?, next_attempt_at = ?, last_error = ?
               WHERE user_id = ? AND week_start = ? AND attempts = ? AND status = 'SENDING'""",
            status,
            if (status == "SENT") Timestamp.from(clock.instant()) else null,
            nextAttemptAt?.let(Timestamp::from),
            error?.take(200),
            userId, Date.valueOf(week.from), attempt,
        )
    }

    private fun skip(reason: String) =
        registry.counter("weekly_behavior_report_total", "outcome", "skipped", "reason", reason).increment()

    /** 오래된 기록 정리 — 한 번 보장에는 이번 주 행만 필요하다. 운영 확인용으로 [RETENTION_WEEKS]주 남긴다. */
    private fun purge(week: KstPeriod) {
        jdbc.update("DELETE FROM weekly_report_sends WHERE week_start < ?", Date.valueOf(week.from.minusWeeks(RETENTION_WEEKS)))
    }

    companion object {
        private const val RETENTION_WEEKS = 12L

        /**
         * 다시 보내도 되는 실패인가. 메시지를 만들 수 없거나(영구) 수신 주소가 거부되면(영구) false.
         * 그 밖의 SMTP 거부·연결 실패·인증 실패(설정 복구 뒤 성공할 수 있다)는 true.
         */
        fun isTransient(e: MailException): Boolean = when (e) {
            is MailParseException, is MailPreparationException -> false
            is MailSendException -> e.failedMessages.values.none { it is SendFailedException && !it.invalidAddresses.isNullOrEmpty() }
            else -> true
        }
    }
}
