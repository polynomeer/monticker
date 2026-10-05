package com.monticker.api.quant.application

import com.monticker.api.quant.domain.DailyCandle
import com.monticker.api.quant.domain.QuantAuxData
import com.monticker.api.quant.domain.RuleCondition
import com.monticker.api.quant.domain.RuleGroup
import com.monticker.api.quant.domain.SentimentCount
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class AuxIndicatorsTest {

    // 2026-03-02(월) ~ 03-06(금), 03-09(월)
    private val days = listOf(2, 3, 4, 5, 6, 9).map { LocalDate.of(2026, 3, it) }
    private val candles = days.map {
        DailyCandle(it, BigDecimal(100), BigDecimal(100), BigDecimal(100), BigDecimal(100), 1000L)
    }

    private fun entry(cond: RuleCondition, aux: QuantAuxData, idx: Int = candles.lastIndex) =
        RuleEvaluator.evaluateEntry(RuleGroup("AND", listOf(cond)), candles, idx, aux)

    // ── 이용 가능일(미래 정보 누수 방지) ──────────────────────────────────────────

    @Test
    fun `information published after the 15h30 KST close becomes usable the next day`() {
        assertThat(AuxIndicators.availableDate(Instant.parse("2026-03-05T06:29:00Z"))).isEqualTo(LocalDate.of(2026, 3, 5)) // 15:29 KST
        assertThat(AuxIndicators.availableDate(Instant.parse("2026-03-05T06:30:00Z"))).isEqualTo(LocalDate.of(2026, 3, 6)) // 15:30 KST
    }

    // ── NEWS_SENTIMENT ───────────────────────────────────────────────────────────

    @Test
    fun `news sentiment uses the net score over the last N trading days including the weekend`() {
        // period=1 at 03-09(월): 창 = (03-06, 03-09] → 토·일·월
        val aux = QuantAuxData(sentimentByDate = mapOf(
            LocalDate.of(2026, 3, 7) to SentimentCount(positive = 3),
            LocalDate.of(2026, 3, 9) to SentimentCount(negative = 1),
            LocalDate.of(2026, 3, 6) to SentimentCount(negative = 10), // 창 밖
        ))
        val cond = RuleCondition("NEWS_SENTIMENT", "GT", mapOf("period" to 1), value = 0.4)

        assertThat(entry(cond, aux)).isTrue() // (3-1)/4 = 0.5
        assertThat(entry(cond.copy(value = 0.6), aux)).isFalse()
    }

    @Test
    fun `news sentiment does not fire when there is no scored article in the window`() {
        val cond = RuleCondition("NEWS_SENTIMENT", "LT", mapOf("period" to 3), value = 1.0)

        assertThat(entry(cond, QuantAuxData.EMPTY)).isFalse()
    }

    @Test
    fun `news sentiment needs period trading days of history`() {
        val aux = QuantAuxData(sentimentByDate = mapOf(days[0] to SentimentCount(positive = 1)))
        val cond = RuleCondition("NEWS_SENTIMENT", "GT", mapOf("period" to 5), value = 0.0)

        assertThat(entry(cond, aux, idx = 2)).isFalse()
    }

    // ── DISCLOSURE ───────────────────────────────────────────────────────────────

    @Test
    fun `disclosure fires when a matching category was published within the window`() {
        val aux = QuantAuxData(disclosuresByDate = mapOf(
            LocalDate.of(2026, 3, 5) to AuxIndicators.classifyDisclosure("주요사항보고서(자기주식취득결정)"),
        ))

        // 03-09 기준 period=3 → 창 (03-04, 03-09], period=2 → (03-05, 03-09]
        assertThat(entry(RuleCondition("DISCLOSURE", "BUYBACK", mapOf("period" to 3)), aux)).isTrue()
        assertThat(entry(RuleCondition("DISCLOSURE", "BUYBACK", mapOf("period" to 2)), aux)).isFalse()
        assertThat(entry(RuleCondition("DISCLOSURE", "RIGHTS_ISSUE", mapOf("period" to 3)), aux)).isFalse()
    }

    @Test
    fun `disclosure categories come from the DART report name`() {
        assertThat(AuxIndicators.classifyDisclosure("분기보고서 (2026.03)")).contains("EARNINGS", "ANY")
        assertThat(AuxIndicators.classifyDisclosure("주요사항보고서(유상증자결정)")).contains("RIGHTS_ISSUE")
        assertThat(AuxIndicators.classifyDisclosure("주요사항보고서(자기주식처분결정)")).doesNotContain("BUYBACK")
        assertThat(AuxIndicators.classifyDisclosure("현금ㆍ현물배당결정")).contains("DIVIDEND")
        assertThat(AuxIndicators.classifyDisclosure("임원ㆍ주요주주특정증권등소유상황보고서")).contains("INSIDER")
        assertThat(AuxIndicators.classifyDisclosure("회사합병결정")).contains("MNA")
    }
}
