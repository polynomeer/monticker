package com.monticker.api.common.time

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class KstPeriodTest {

    @Test
    fun `boundaries are KST midnights regardless of the JVM time zone`() {
        val p = KstPeriod(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))
        assertThat(p.start).isEqualTo(Instant.parse("2026-10-04T15:00:00Z"))
        assertThat(p.endExclusive).isEqualTo(Instant.parse("2026-10-11T15:00:00Z"))
        assertThat(p.days).hasSize(7).startsWith(LocalDate.of(2026, 10, 5)).endsWith(LocalDate.of(2026, 10, 11))
    }

    @Test
    fun `week boundary is Monday 00_00 KST`() {
        // 일요일 23:59:59 KST = 일요일 14:59:59Z → 그 주(월 10/5 ~ 일 10/11)
        val sundayLate = Instant.parse("2026-10-11T14:59:59Z")
        assertThat(KstPeriod.weekOf(KstPeriod.today(sundayLate))).isEqualTo(KstPeriod(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)))
        // 월요일 00:00 KST = 일요일 15:00Z → 새 주(월 10/12 ~)
        val mondayMidnight = Instant.parse("2026-10-11T15:00:00Z")
        assertThat(KstPeriod.weekOf(KstPeriod.today(mondayMidnight)).from).isEqualTo(LocalDate.of(2026, 10, 12))
        // UTC 기준으로는 아직 일요일이지만 KST로는 월요일이다
        assertThat(KstPeriod.weekStart(LocalDate.of(2026, 10, 12))).isEqualTo(LocalDate.of(2026, 10, 12))
        assertThat(KstPeriod.weekStart(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2026, 10, 12))
    }

    @Test
    fun `parse defaults to the last N days ending today in KST`() {
        // 2026-10-08 00:30 KST = 10-07 15:30Z — UTC로는 7일이지만 KST 오늘은 8일
        val now = Instant.parse("2026-10-07T15:30:00Z")
        assertThat(KstPeriod.parse(null, null, defaultDays = 7, now = now))
            .isEqualTo(KstPeriod(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8)))
    }

    @Test
    fun `parse accepts exactly one year and rejects longer, reversed or half-open ranges`() {
        val to = LocalDate.of(2026, 10, 8)
        assertThat(KstPeriod.parse(to.minusYears(1), to, 7).from).isEqualTo(LocalDate.of(2025, 10, 8))
        assertThatThrownBy { KstPeriod.parse(to.minusYears(1).minusDays(1), to, 7) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("최대")
        assertThatThrownBy { KstPeriod.parse(to, to.minusDays(1), 7) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { KstPeriod.parse(to, null, 7) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { KstPeriod.parse(null, to, 7) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
