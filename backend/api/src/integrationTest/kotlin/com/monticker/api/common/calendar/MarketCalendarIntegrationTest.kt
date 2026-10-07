package com.monticker.api.common.calendar

import com.monticker.api.paper.domain.SettlementStatus
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * ADR-086 — V83(market_holidays·market_calendar_years)가 실제 Postgres에 적용되고, 리포지토리·캘린더가 그 데이터로 동작하는지.
 * 정산 리포지토리의 새 JPQL(상태 필터·기간 집계·정산일 재정렬)도 실제 Hibernate로 검증한다 — 단위 테스트는 목이라 쿼리 오타를 못 잡는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MarketCalendarIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("monticker")
                .withUsername("monticker")
                .withPassword("monticker")

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var settlements: PaperSettlementRepository

    private val repo by lazy { MarketHolidayRepository(jdbc) }

    @Test
    fun `V83 seeds 2026 and 2027 and the repository reads them back`() {
        val holidays = repo.findHolidays()
        assertThat(holidays.filter { it.date.year == 2026 }).hasSize(17)
        assertThat(holidays.filter { it.date.year == 2027 }).hasSize(15)
        assertThat(holidays.map { it.source }.toSet()).containsExactly("SEED_RULE_DERIVED")
        assertThat(holidays.first { it.date == LocalDate.of(2026, 9, 25) }.name).isEqualTo("추석")
        assertThat(repo.findCoveredYears()).containsExactlyInAnyOrder(2026, 2027)
        assertThat(repo.findHolidays("NYSE")).isEmpty()

        // DB에서 읽은 캘린더와 마이그레이션 파일을 파싱한 테스트 시드가 같은 데이터다
        assertThat(KrxCalendar(holidays, repo.findCoveredYears()).sameDataAs(V83Seed.calendar())).isTrue()
    }

    @Test
    fun `weekend rows are rejected by the check constraint`() {
        assertThatThrownBy {
            jdbc.update("INSERT INTO market_holidays (market, holiday_date, name, source) VALUES ('KRX', DATE '2026-10-03', '개천절', 'X')")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `settlement repository status filter, period aggregate and conditional move work on real SQL`() {
        val userId = jdbc.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, 'cal') RETURNING id", Long::class.java, "cal-${System.nanoTime()}@test.local",
        )!!
        val stockId = jdbc.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, '캘린더', 'KOSPI', 'KRX') RETURNING id",
            Long::class.java, "C${System.nanoTime() % 100000}",
        )!!
        fun trade(side: String, at: Instant) = jdbc.queryForObject(
            "INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at) VALUES (?, ?, ?, 1, 1000, 1000, ?) RETURNING id",
            Long::class.java, userId, stockId, side, Timestamp.from(at),
        )!!
        fun settlement(tradeId: Long, side: String, net: String, status: String, date: LocalDate) = jdbc.queryForObject(
            """INSERT INTO paper_settlements (trade_id, user_id, stock_id, side, quantity, fill_price, gross_amount, fee, tax, net_amount, status, settle_date)
               VALUES (?, ?, ?, ?, 1, 1000, 1000, 0, 0, ?, ?, ?) RETURNING id""",
            Long::class.java, tradeId, userId, stockId, side, BigDecimal(net), status, Date.valueOf(date),
        )!!

        // 9/22(화) 체결 — 옛 규칙으로 9/24(추석 연휴)에 잡힌 PENDING 매수
        val buyTradedAt = Instant.parse("2026-09-22T01:00:00Z")
        val pendingBuy = settlement(trade("BUY", buyTradedAt), "BUY", "1000", "PENDING", LocalDate.of(2026, 9, 24))
        settlement(trade("SELL", Instant.parse("2026-09-18T01:00:00Z")), "SELL", "900", "SETTLED", LocalDate.of(2026, 9, 22))
        settlement(trade("SELL", Instant.parse("2026-09-18T02:00:00Z")), "SELL", "50", "FAILED", LocalDate.of(2026, 9, 22))

        val settled = settlements.findAllByUserIdAndStatusOrderBySettleDateDesc(userId, SettlementStatus.SETTLED, PageRequest.of(0, 20))
        assertThat(settled.totalElements).isEqualTo(1)

        val sums = settlements.sumByDateAndStatus(userId, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27))
            .associateBy { it.settleDate to it.status }
        assertThat(sums.getValue(LocalDate.of(2026, 9, 24) to SettlementStatus.PENDING).signedNet).isEqualByComparingTo("-1000")
        assertThat(sums.getValue(LocalDate.of(2026, 9, 22) to SettlementStatus.SETTLED).signedNet).isEqualByComparingTo("900")
        assertThat(sums.getValue(LocalDate.of(2026, 9, 22) to SettlementStatus.FAILED).count).isEqualTo(1)

        val upcoming = settlements.findUpcomingPendingDates(LocalDate.of(2026, 9, 23)).filter { it.id == pendingBuy }
        assertThat(upcoming).singleElement().satisfies({ assertThat(it.tradedAt).isEqualTo(buyTradedAt) })

        // 조건부 UPDATE: 읽은 날짜가 맞을 때만 1건, 다시 하면 0건
        assertThat(settlements.moveSettleDate(pendingBuy, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 28))).isEqualTo(1)
        assertThat(settlements.moveSettleDate(pendingBuy, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 28))).isEqualTo(0)
        assertThat(jdbc.queryForObject("SELECT settle_date FROM paper_settlements WHERE id = ?", LocalDate::class.java, pendingBuy))
            .isEqualTo(LocalDate.of(2026, 9, 28))
    }
}
