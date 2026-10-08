package com.monticker.api.wallet.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.common.time.KstPeriod
import com.monticker.api.wallet.application.Ratio
import com.monticker.api.wallet.application.WeeklyRatio
import com.monticker.api.wallet.application.WeeklyScore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.mail.SendFailedException
import jakarta.mail.internet.InternetAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailSendException
import java.time.Instant
import java.time.LocalDate

class WeeklyBehaviorReportTest {

    private val week = KstPeriod(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))

    // ── 대상 주 ──

    @Test
    fun `report week flips exactly at Monday 00_00 KST regardless of JVM time zone`() {
        // 월요일 00:00 KST = 일요일 15:00Z → 직전 주(10/5~10/11)
        assertThat(ReportWeek.at(Instant.parse("2026-10-11T15:00:00Z"))).isEqualTo(week)
        // 월요일 08:00 KST
        assertThat(ReportWeek.at(Instant.parse("2026-10-11T23:00:00Z"))).isEqualTo(week)
        // 1초 전(일요일 23:59:59 KST)에는 아직 그 주가 진행 중이라 그 전 주(9/28~10/4)
        assertThat(ReportWeek.at(Instant.parse("2026-10-11T14:59:59Z")))
            .isEqualTo(KstPeriod(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)))
        // 경계 시각은 KST 자정
        assertThat(week.start).isEqualTo(Instant.parse("2026-10-04T15:00:00Z"))
        assertThat(week.endExclusive).isEqualTo(Instant.parse("2026-10-11T15:00:00Z"))
    }

    // ── 대상 판정 ──

    @Test
    fun `eligibility requires verified, not deleted, last-week trades and all three switches`() {
        val ok = ReportRecipient(deleted = false, emailVerified = true, tradesLastWeek = 1, preference = ReportPreference())
        assertThat(ok.eligible).isTrue()
        assertThat(ok.copy(deleted = true).eligible).isFalse()
        assertThat(ok.copy(emailVerified = false).eligible).isFalse()
        assertThat(ok.copy(tradesLastWeek = 0).eligible).isFalse()
        assertThat(ok.copy(preference = ReportPreference(weeklyReportEmail = false)).eligible).isFalse()
        assertThat(ok.copy(preference = ReportPreference(emailEnabled = false)).eligible).isFalse()
        assertThat(ok.copy(preference = ReportPreference(allEnabled = false)).eligible).isFalse()
    }

    @Test
    fun `legacy redis preferences default to on, honor opt-out, and withhold the page when redis fails`() {
        val ops = mockk<ValueOperations<String, String>>()
        val redis = mockk<StringRedisTemplate> { every { opsForValue() } returns ops }
        every { ops.multiGet(listOf("notif:pref:1", "notif:pref:2", "notif:pref:3", "notif:pref:4")) } returns
            listOf(null, """{"weeklyReportEmail":false}""", "not-json", """{"emailEnabled":false,"weeklyReportEmail":true}""")
        val job = job(redis)

        val prefs = job.legacyPreferences(listOf(1, 2, 3, 4))
        assertThat(prefs[1]!!.wantsReport).isTrue()   // 옛 값 없음 → 기본(켜짐)
        assertThat(prefs[2]!!.wantsReport).isFalse()  // 옛 값으로 끔
        assertThat(prefs[3]!!.wantsReport).isTrue()   // 해석 실패 → 기본(설정 화면과 같다)
        assertThat(prefs[4]!!.wantsReport).isFalse()  // 이메일 채널 끔

        every { ops.multiGet(any()) } throws RuntimeException("down")
        assertThat(job.legacyPreferences(listOf(1))).isEmpty()
    }

    // ── 실패 분류 ──

    @Test
    fun `only definite non-delivery that may succeed later is retried`() {
        assertThat(WeeklyBehaviorReportJob.isTransient(MailSendException("connect refused"))).isTrue()
        assertThat(WeeklyBehaviorReportJob.isTransient(MailAuthenticationException("bad creds"))).isTrue()
        assertThat(WeeklyBehaviorReportJob.isTransient(MailParseException("bad"))).isFalse()
        val rejected = SendFailedException("550", null, emptyArray(), emptyArray(), arrayOf(InternetAddress("x@test.local")))
        assertThat(WeeklyBehaviorReportJob.isTransient(MailSendException(mapOf<Any, Exception>("m" to rejected)))).isFalse()
    }

    // ── 본문 ──

    private fun report(
        plan: WeeklyRatio = WeeklyRatio(Ratio(3, 4), Ratio(1, 2)),
        stop: WeeklyRatio = WeeklyRatio(Ratio(1, 2), Ratio(0, 0)),
        score: WeeklyScore = WeeklyScore(82.0, 74.5, 7, 5),
        emotions: List<EmotionShare> = listOf(EmotionShare("FOMO", 2, 50.0), EmotionShare("PLANNED", 2, 50.0)),
    ) = WeeklyBehaviorReport(week, buyCount = 5, sellCount = 3, previousTradeCount = 10, plan, stop, score, emotions.sumOf { it.count }, emotions)

    @Test
    fun `content summarizes behavior with week-over-week deltas`() {
        val mail = WeeklyBehaviorReportRenderer.render(report(), "투자자", "https://app.monticker.io/")

        assertThat(mail.subject).isEqualTo("[monticker] 주간 투자 행동 리포트 · 10/5(월) ~ 10/11(일)")
        assertThat(mail.text)
            .contains("모의 체결: 8건 (매수 5 · 매도 3) (전주 대비 −2건)")
            .contains("계획 준수율: 75% (3/4) (전주 대비 +25.0%p)")
            .contains("손절 준수율: 50% (1/2)")          // 지난주 분모 0 → 대비 없음
            .contains("행동 점수(주간 평균): 82.0점 (전주 대비 +7.5점)")
            .contains("FOMO 2건 (50%)").contains("계획대로 2건 (50%)")
            .contains("https://app.monticker.io/settings/notifications")
            .contains(WeeklyBehaviorReportRenderer.DISCLAIMER)
        assertThat(mail.text).doesNotContain("손절 준수율: 50% (1/2) (전주")
        assertThat(mail.html).contains("https://app.monticker.io/settings/notifications").contains("투자 권유가 아닙니다")
    }

    @Test
    fun `missing values render as a dash, never a made-up number`() {
        val mail = WeeklyBehaviorReportRenderer.render(
            report(plan = WeeklyRatio(Ratio(0, 0), Ratio(0, 0)), stop = WeeklyRatio(Ratio(0, 0), Ratio(0, 0)),
                score = WeeklyScore(null, null, 0, 0), emotions = emptyList()),
            "a", "http://localhost:3000",
        )
        assertThat(mail.text).contains("계획 준수율: —").contains("손절 준수율: —").contains("행동 점수(주간 평균): —")
            .contains("감정 태그를 남긴 체결이 없습니다")
    }

    @Test
    fun `content has no investment advice or stock recommendation wording`() {
        val mail = WeeklyBehaviorReportRenderer.render(report(), "투자자", "http://localhost:3000")
        val forbidden = listOf(
            "추천", "매수하세요", "매도하세요", "사세요", "파세요", "유망", "목표가", "수익 보장", "확실한 수익",
            "종목", "급등", "지금 사", "지금 팔", "매수 타이밍", "매도 타이밍",
        )
        for (body in listOf(mail.subject, mail.text, mail.html)) {
            forbidden.forEach { assertThat(body).doesNotContain(it) }
        }
    }

    @Test
    fun `user-controlled nickname is html escaped`() {
        val mail = WeeklyBehaviorReportRenderer.render(report(), "<script>alert(1)</script>&\"'", "http://localhost:3000")
        assertThat(mail.html).doesNotContain("<script>").contains("&lt;script&gt;alert(1)&lt;/script&gt;&amp;&quot;&#39;")
        // 평문 본문은 이스케이프하지 않는다(HTML이 아니다)
        assertThat(mail.text).startsWith("<script>alert(1)</script>&\"'님")
    }

    private fun job(redis: StringRedisTemplate) = WeeklyBehaviorReportJob(
        jdbc = mockk(), scoreDetails = mockk(), emotions = mockk(), mailSender = mockk(), redis = redis,
        objectMapper = ObjectMapper(), registry = SimpleMeterRegistry(),
    )
}
