package com.monticker.api.event.infrastructure

import com.monticker.api.event.application.EventAggregateService
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-087 — 이벤트 집계 SQL을 실제 Postgres(V84 인덱스 포함)에서 검증한다.
 *
 * 핵심은 **KST 자정 경계**다: 하루의 경계를 `AT TIME ZONE`으로 컬럼에 씌워 비교하면 세션 타임존에 따라 결과가
 * 달라진다(CI에서만 깨졌던 전례). 이 테스트는 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로도 돌려
 * JVM(→ JDBC 세션) 타임존이 바뀌어도 같은 결과인지 확인한다.
 *
 * 컨테이너는 다른 통합 테스트와 공유되므로, 다른 테스트가 쓰지 않을 과거 날짜와 고유 종목만 쓴다.
 */
class EventAggregateRepositoryIntegrationTest : PostgresIntegrationTest() {

    private val repo by lazy { EventAggregateRepository(jdbcTemplate) }

    private fun createStock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, '집계테스트', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "AG" + UUID.randomUUID().toString().take(8),
    )!!

    private fun event(stockId: Long, type: String, at: Instant) {
        jdbcTemplate.update(
            "INSERT INTO stock_events (stock_id, event_type, title, event_time, importance_score) VALUES (?, ?, 't', ?, 5)",
            stockId, type, OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
        )
    }

    @Test
    fun `KST 하루 경계 — 자정 직전은 전날, 자정부터 다음 자정 직전까지가 그날이다`() {
        val day = LocalDate.of(2019, 3, 15)
        val from = EventAggregateService.dayStart(day)                 // 2019-03-14T15:00:00Z
        val to = EventAggregateService.dayStart(day.plusDays(1))       // 2019-03-15T15:00:00Z
        assertThat(from).isEqualTo(Instant.parse("2019-03-14T15:00:00Z"))

        val a = createStock()
        val b = createStock()
        event(a, "PRICE_SPIKE", Instant.parse("2019-03-14T14:59:59Z"))   // KST 03-14 23:59:59 → 제외
        event(a, "PRICE_SPIKE", Instant.parse("2019-03-14T15:00:00Z"))   // KST 03-15 00:00:00 → 포함
        event(a, "PRICE_SPIKE", Instant.parse("2019-03-15T03:00:00Z"))   // 같은 종목 두 번째 급등 → 건수 2, 종목 1
        event(b, "PRICE_DROP", Instant.parse("2019-03-15T14:59:59Z"))    // KST 03-15 23:59:59 → 포함
        event(b, "NEWS_PUBLISHED", Instant.parse("2019-03-15T15:00:00Z")) // KST 03-16 00:00 → 제외

        val tallies = repo.countByType(from, to).associateBy { it.eventType }
        assertThat(tallies.keys).containsExactlyInAnyOrder("PRICE_SPIKE", "PRICE_DROP")
        assertThat(tallies["PRICE_SPIKE"]!!.count).isEqualTo(2)
        assertThat(tallies["PRICE_SPIKE"]!!.stockCount).isEqualTo(1)
        assertThat(tallies["PRICE_DROP"]!!.count).isEqualTo(1)

        val summary = EventAggregateService.assemble(day, repo.countByType(from, to))
        assertThat(summary.total).isEqualTo(3)
        assertThat(summary.surgeStocks).isEqualTo(1)
        assertThat(summary.plungeStocks).isEqualTo(1)
    }

    @Test
    fun `이벤트가 없는 날은 빈 결과다`() {
        val day = LocalDate.of(2018, 1, 1)
        assertThat(repo.countByType(EventAggregateService.dayStart(day), EventAggregateService.dayStart(day.plusDays(1)))).isEmpty()
    }

    @Test
    fun `종목별 건수 — 요청한 종목만, 구간 안만 센다`() {
        val a = createStock()
        val b = createStock()
        val other = createStock()
        val from = Instant.parse("2020-06-01T15:00:00Z")
        val to = Instant.parse("2020-07-01T15:00:00Z")
        event(a, "NEWS_PUBLISHED", Instant.parse("2020-06-01T15:00:00Z"))      // 경계 포함
        event(a, "DISCLOSURE_PUBLISHED", Instant.parse("2020-06-10T00:00:00Z"))
        event(a, "NEWS_PUBLISHED", Instant.parse("2020-07-01T15:00:00Z"))      // 끝 경계 제외
        event(a, "NEWS_PUBLISHED", Instant.parse("2020-06-01T14:59:00Z"))      // 시작 전 제외
        event(other, "NEWS_PUBLISHED", Instant.parse("2020-06-10T00:00:00Z"))  // 요청하지 않은 종목

        val counts = repo.countByStock(listOf(a, b), from, to)
        assertThat(counts).containsExactlyEntriesOf(mapOf(a to 2L))   // b는 0건이라 없음(서비스가 0으로 채운다)
        assertThat(repo.countByStock(emptyList(), from, to)).isEmpty()
    }

    @Test
    fun `V84 인덱스가 있다`() {
        val n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'stock_events' AND indexname = 'idx_stock_events_time_type_stock'",
            Int::class.java,
        )
        assertThat(n).isEqualTo(1)
    }
}
