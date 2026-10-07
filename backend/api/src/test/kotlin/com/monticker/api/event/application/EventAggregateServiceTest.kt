package com.monticker.api.event.application

import com.monticker.api.event.domain.EventType
import com.monticker.api.event.infrastructure.EventAggregateRepository
import com.monticker.api.event.infrastructure.EventTypeTally
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class EventAggregateServiceTest {

    private val repo = mockk<EventAggregateRepository>()
    private val service = EventAggregateService(repo)

    // ── 날짜 경계 ─────────────────────────────────────────────────────────

    @Test
    fun `오늘은 KST 날짜다 — UTC 15시 이후는 다음 날`() {
        assertThat(EventAggregateService.today(Instant.parse("2026-10-06T14:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 6))
        assertThat(EventAggregateService.today(Instant.parse("2026-10-06T15:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 7))
    }

    @Test
    fun `하루의 시작은 KST 자정을 Instant로 바꾼 값이다`() {
        assertThat(EventAggregateService.dayStart(LocalDate.of(2026, 10, 7))).isEqualTo(Instant.parse("2026-10-06T15:00:00Z"))
    }

    @Test
    fun `summary 는 KST 하루를 반열림 구간으로 조회한다`() {
        every { repo.countByType(any(), any()) } returns emptyList()
        service.summary(LocalDate.of(2026, 10, 7))
        verify { repo.countByType(Instant.parse("2026-10-06T15:00:00Z"), Instant.parse("2026-10-07T15:00:00Z")) }
    }

    @Test
    fun `요약 날짜 — 생략하면 오늘, 미래와 너무 먼 과거는 거부`() {
        val now = Instant.parse("2026-10-06T16:00:00Z")   // KST 2026-10-07 01:00
        assertThat(EventAggregateService.resolveSummaryDate(null, now)).isEqualTo(LocalDate.of(2026, 10, 7))
        assertThat(EventAggregateService.resolveSummaryDate(LocalDate.of(2026, 10, 6), now)).isEqualTo(LocalDate.of(2026, 10, 6))
        assertThatThrownBy { EventAggregateService.resolveSummaryDate(LocalDate.of(2026, 10, 8), now) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EventAggregateService.resolveSummaryDate(LocalDate.of(2025, 1, 1), now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── 집계 조립 ─────────────────────────────────────────────────────────

    @Test
    fun `모든 유형을 0으로라도 채우고 급등·급락 종목 수를 낸다`() {
        val s = EventAggregateService.assemble(
            LocalDate.of(2026, 10, 7),
            listOf(
                EventTypeTally("PRICE_SPIKE", 5, 3),
                EventTypeTally("PRICE_DROP", 2, 2),
                EventTypeTally("NEWS_PUBLISHED", 10, 7),
            ),
        )
        assertThat(s.total).isEqualTo(17)
        assertThat(s.surgeStocks).isEqualTo(3)
        assertThat(s.plungeStocks).isEqualTo(2)
        assertThat(s.byType.map { it.eventType }).containsAll(EventType.entries.map { it.name })
        assertThat(s.byType.first { it.eventType == "VOLUME_SURGE" }.count).isZero()
    }

    @Test
    fun `빈 날은 전부 0이다`() {
        val s = EventAggregateService.assemble(LocalDate.of(2026, 10, 7), emptyList())
        assertThat(s.total).isZero()
        assertThat(s.surgeStocks).isZero()
        assertThat(s.plungeStocks).isZero()
        assertThat(s.byType).allMatch { it.count == 0L }
    }

    @Test
    fun `enum 에 없는 유형도 버리지 않는다`() {
        val s = EventAggregateService.assemble(LocalDate.of(2026, 10, 7), listOf(EventTypeTally("NEW_KIND", 4, 1)))
        assertThat(s.total).isEqualTo(4)
        assertThat(s.byType.last().eventType).isEqualTo("NEW_KIND")
    }

    // ── 종목별 건수 요청 검증 ───────────────────────────────────────────────

    @Test
    fun `종목 ID 는 중복 제거·정렬되고 캐시 키가 순서에 흔들리지 않는다`() {
        val now = Instant.parse("2026-10-06T16:00:00Z")
        val a = EventAggregateService.parseCountQuery("3, 1,3,2", 30, now)
        val b = EventAggregateService.parseCountQuery("2,1,3", 30, now)
        assertThat(a.stockIds).containsExactly(1L, 2L, 3L)
        assertThat(a.cacheKey).isEqualTo(b.cacheKey)
    }

    @Test
    fun `기간은 KST (오늘 - days) 자정부터 오늘 끝까지`() {
        val q = EventAggregateService.parseCountQuery("1", 30, Instant.parse("2026-10-06T16:00:00Z"))   // KST 10-07
        assertThat(q.from).isEqualTo(Instant.parse("2026-09-06T15:00:00Z"))   // KST 09-07 00:00
        assertThat(q.to).isEqualTo(Instant.parse("2026-10-07T15:00:00Z"))     // KST 10-08 00:00
    }

    @Test
    fun `잘못된 종목·기간은 400 대상 예외`() {
        listOf("", " , ", "abc", "1,-2", "0").forEach { raw ->
            assertThatThrownBy { EventAggregateService.parseCountQuery(raw, 30) }
                .`as`(raw).isInstanceOf(IllegalArgumentException::class.java)
        }
        val tooMany = (1..EventAggregateService.MAX_COUNT_STOCKS + 1).joinToString(",")
        assertThatThrownBy { EventAggregateService.parseCountQuery(tooMany, 30) }.isInstanceOf(IllegalArgumentException::class.java)
        val repeated = List(EventAggregateService.MAX_COUNT_STOCKS * 2 + 1) { "1" }.joinToString(",")
        assertThatThrownBy { EventAggregateService.parseCountQuery(repeated, 30) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EventAggregateService.parseCountQuery("1", 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EventAggregateService.parseCountQuery("1", EventAggregateService.MAX_COUNT_DAYS + 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `이벤트가 없는 종목도 0건으로 돌려준다`() {
        val q = EventAggregateService.parseCountQuery("1,2", 7)
        every { repo.countByStock(listOf(1L, 2L), q.from, q.to) } returns mapOf(2L to 5L)
        assertThat(service.counts(q)).containsExactly(StockEventCount(1, 0), StockEventCount(2, 5))
    }
}
