package com.monticker.api.analytics.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AnalysisPeriodTest {

    private val today = LocalDate.of(2026, 10, 8)

    @Test
    fun `defaults to one year ending today in KST`() {
        val p = AnalysisPeriod.resolve(null, null, null, today)

        assertThat(p.label).isEqualTo("1Y")
        assertThat(p.from).isEqualTo(LocalDate.of(2025, 10, 8))
        assertThat(p.to).isEqualTo(today)
    }

    @Test
    fun `presets go back the given number of months and are case-insensitive`() {
        assertThat(AnalysisPeriod.resolve("3m", null, null, today).from).isEqualTo(LocalDate.of(2026, 7, 8))
        assertThat(AnalysisPeriod.resolve("6M", null, null, today).from).isEqualTo(LocalDate.of(2026, 4, 8))
        assertThat(AnalysisPeriod.resolve("2Y", null, null, today).from).isEqualTo(LocalDate.of(2024, 10, 8))
    }

    @Test
    fun `unknown preset is rejected`() {
        assertThatThrownBy { AnalysisPeriod.resolve("10Y", null, null, today) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `preset with from or to is rejected`() {
        assertThatThrownBy { AnalysisPeriod.resolve("1Y", LocalDate.of(2026, 1, 1), null, today) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `custom range within bounds is accepted, and from-to alone implies custom`() {
        val p = AnalysisPeriod.resolve(null, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), today)

        assertThat(p.label).isEqualTo("CUSTOM")
        assertThat(p.from).isEqualTo(LocalDate.of(2024, 1, 1))
        assertThat(p.to).isEqualTo(LocalDate.of(2025, 1, 1))
    }

    @Test
    fun `custom range requires both ends`() {
        assertThatThrownBy { AnalysisPeriod.resolve("CUSTOM", LocalDate.of(2026, 1, 1), null, today) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `custom range must be ordered and not end in the future`() {
        assertThatThrownBy { AnalysisPeriod.resolve("CUSTOM", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 1, 1), today) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AnalysisPeriod.resolve("CUSTOM", LocalDate.of(2026, 1, 1), today.plusDays(1), today) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `custom range is bounded between 60 days and 3 years`() {
        assertThatThrownBy { AnalysisPeriod.resolve("CUSTOM", today.minusDays(59), today, today) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("최소 60일")
        assertThat(AnalysisPeriod.resolve("CUSTOM", today.minusDays(60), today, today).from).isEqualTo(today.minusDays(60))
        assertThat(AnalysisPeriod.resolve("CUSTOM", today.minusYears(3), today, today).from).isEqualTo(today.minusYears(3))
        assertThatThrownBy { AnalysisPeriod.resolve("CUSTOM", today.minusYears(3).minusDays(1), today, today) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("최대 3년")
    }
}
