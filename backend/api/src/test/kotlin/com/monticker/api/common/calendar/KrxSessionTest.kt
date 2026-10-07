package com.monticker.api.common.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

class KrxSessionTest {

    private val cal = V83Seed.calendar()

    /** KST 시각 → Instant (테스트 JVM 시간대와 무관) */
    private fun kst(s: String): Instant = ZonedDateTime.of(java.time.LocalDateTime.parse(s), KrxCalendar.ZONE).toInstant()
    private fun kstZ(s: String): ZonedDateTime = ZonedDateTime.of(java.time.LocalDateTime.parse(s), KrxCalendar.ZONE)

    @Test
    fun `phases on a regular business day`() {
        assertThat(KrxSession.status(kst("2026-10-07T07:00"), cal).phase).isEqualTo(MarketPhase.CLOSED)
        assertThat(KrxSession.status(kst("2026-10-07T08:45"), cal).phase).isEqualTo(MarketPhase.PRE)
        val open = KrxSession.status(kst("2026-10-07T10:00"), cal)
        assertThat(open.phase).isEqualTo(MarketPhase.OPEN)
        assertThat(open.nextClose).isEqualTo(kstZ("2026-10-07T15:30"))
        assertThat(open.nextOpen).isEqualTo(kstZ("2026-10-08T09:00"))
        assertThat(KrxSession.status(kst("2026-10-07T15:30"), cal).phase).isEqualTo(MarketPhase.POST)
        assertThat(KrxSession.status(kst("2026-10-07T18:00"), cal).phase).isEqualTo(MarketPhase.CLOSED)
    }

    @Test
    fun `holiday is closed all day with its name and next open skips the chain`() {
        val s = KrxSession.status(kst("2026-09-24T10:00"), cal)   // 추석 연휴
        assertThat(s.phase).isEqualTo(MarketPhase.CLOSED)
        assertThat(s.isTradingDay).isFalse()
        assertThat(s.holidayName).isEqualTo("추석 연휴")
        assertThat(s.openAt).isNull()
        assertThat(s.nextOpen).isEqualTo(kstZ("2026-09-28T09:00"))
        assertThat(s.nextClose).isEqualTo(kstZ("2026-09-28T15:30"))
        assertThat(s.calendarCovered).isTrue()
        assertThat(s.calendarCoverageUntil).isEqualTo(LocalDate.of(2027, 12, 31))
    }

    @Test
    fun `first trading day of the year opens at 10`() {
        val s = KrxSession.status(kst("2027-01-04T09:30"), cal)
        assertThat(s.phase).isEqualTo(MarketPhase.PRE)
        assertThat(s.openAt).isEqualTo(kstZ("2027-01-04T10:00"))
        // 연말 휴장일 오후에 다음 개장을 물으면 새해 첫 거래일 10:00
        assertThat(KrxSession.status(kst("2026-12-31T14:00"), cal).nextOpen).isEqualTo(kstZ("2027-01-04T10:00"))
    }

    @Test
    fun `status is computed in KST even at UTC midnight boundaries`() {
        // 2026-10-08 00:30 UTC = 09:30 KST 목요일 → 열림 (UTC 날짜로 보면 아직 10/8 00:30 — 장 전으로 잘못 볼 수 있다)
        assertThat(KrxSession.status(Instant.parse("2026-10-08T00:30:00Z"), cal).phase).isEqualTo(MarketPhase.OPEN)
        // 2026-10-08 15:30 UTC = 10/9 00:30 KST 한글날
        val s = KrxSession.status(Instant.parse("2026-10-08T15:30:00Z"), cal)
        assertThat(s.date).isEqualTo(LocalDate.of(2026, 10, 9))
        assertThat(s.holidayName).isEqualTo("한글날")
    }

    @Test
    fun `uncovered year reports coverage gap`() {
        val s = KrxSession.status(kst("2028-03-02T10:00"), cal)
        assertThat(s.calendarCovered).isFalse()
        assertThat(s.calendarCoverageUntil).isNull()
        assertThat(s.phase).isEqualTo(MarketPhase.OPEN)   // 주말 규칙으로는 열림
    }
}
