package com.monticker.worker.alert

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

/** ADR-044 — 이진 탐색이 선형 스캔과 같은 집합을 주고, 준비 전엔 DB 폴백이며, 로드 후엔 DB를 치지 않는다. */
class AlertRuleIndexTest {

    private fun rule(id: Long, type: String, threshold: Double, stockId: Long = 5) =
        AlertRuleRow(id, 10, stockId, type, """{"threshold": $threshold}""")

    @Test
    fun `PRICE_ABOVE 는 threshold 가 price 보다 작은 룰만, PRICE_BELOW 는 큰 룰만 — 경계는 제외`() {
        val rules = listOf(
            rule(1, "PRICE_ABOVE", 100.0), rule(2, "PRICE_ABOVE", 200.0), rule(3, "PRICE_ABOVE", 300.0),
            rule(4, "PRICE_BELOW", 150.0), rule(5, "PRICE_BELOW", 250.0), rule(6, "PRICE_BELOW", 350.0),
            rule(7, "RSI_BELOW", 30.0),
        )
        val sr = AlertRuleIndex.StockRules(rules.shuffled())
        assertThat(sr.aboveTriggered(BigDecimal(250)).map { it.id }).containsExactly(1L, 2L)   // 100,200 < 250
        assertThat(sr.aboveTriggered(BigDecimal(200)).map { it.id }).containsExactly(1L)       // 200은 "초과"가 아니다
        assertThat(sr.belowTriggered(BigDecimal(250)).map { it.id }).containsExactly(6L)       // 350 > 250
        assertThat(sr.belowTriggered(BigDecimal(100)).map { it.id }).containsExactly(6L, 5L, 4L)
        assertThat(sr.others.map { it.id }).containsExactly(7L)
    }

    @Test
    fun `이진 탐색 결과가 선형 스캔과 무작위 입력에서 항상 같다`() {
        val rnd = java.util.Random(42)
        val rules = (1..500L).map { rule(it, if (it % 2 == 0L) "PRICE_ABOVE" else "PRICE_BELOW", rnd.nextInt(10_000).toDouble()) }
        val sr = AlertRuleIndex.StockRules(rules)
        repeat(200) {
            val p = BigDecimal(rnd.nextInt(10_000))
            val linearAbove = rules.filter { it.ruleType == "PRICE_ABOVE" && it.conditionJson.substringAfter(": ").substringBefore("}").toDouble() < p.toDouble() }.map { it.id }.toSet()
            val linearBelow = rules.filter { it.ruleType == "PRICE_BELOW" && it.conditionJson.substringAfter(": ").substringBefore("}").toDouble() > p.toDouble() }.map { it.id }.toSet()
            assertThat(sr.aboveTriggered(p).map { it.id }.toSet()).isEqualTo(linearAbove)
            assertThat(sr.belowTriggered(p).map { it.id }.toSet()).isEqualTo(linearBelow)
        }
    }

    @Test
    fun `로드 전에는 DB 폴백, 로드 후에는 종목 조회에 DB 를 치지 않는다`() {
        val jdbc = mockk<JdbcTemplate>()
        val all = listOf(rule(1, "PRICE_ABOVE", 100.0, stockId = 5), rule(2, "PRICE_ABOVE", 50.0, stockId = 6))
        every { jdbc.query(match<String> { it.contains("stock_id = ?") }, any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(all[0])
        every { jdbc.query(match<String> { it.contains("stock_id IS NOT NULL") && !it.contains("updated_at") }, any<RowMapper<AlertRuleRow>>()) } returns all
        val index = AlertRuleIndex(jdbc, SimpleMeterRegistry())

        assertThat(index.isReady()).isFalse()
        assertThat(index.rulesFor(5).aboveTriggered(BigDecimal(200)).map { it.id }).containsExactly(1L)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) }

        index.loadAll()
        assertThat(index.isReady()).isTrue()
        repeat(1000) { index.rulesFor(5); index.rulesFor(6); index.rulesFor(999) }
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) }   // 더 늘지 않았다
        assertThat(index.rulesFor(6).aboveTriggered(BigDecimal(60)).map { it.id }).containsExactly(2L)
        assertThat(index.rulesFor(999).size).isZero()
    }

    @Test
    fun `reload 는 한 종목만 다시 읽고, 빈 결과면 인덱스에서 제거한다`() {
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.query(match<String> { it.contains("stock_id IS NOT NULL") && !it.contains("updated_at") }, any<RowMapper<AlertRuleRow>>()) } returns listOf(rule(1, "PRICE_ABOVE", 100.0))
        every { jdbc.query(match<String> { it.contains("stock_id = ?") }, any<RowMapper<AlertRuleRow>>(), 5L) } returns emptyList()   // 비활성화됨
        val index = AlertRuleIndex(jdbc, SimpleMeterRegistry())
        index.loadAll()
        assertThat(index.rulesFor(5).size).isEqualTo(1)
        index.reload(5)
        assertThat(index.rulesFor(5).size).isZero()
    }
}
