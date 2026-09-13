package com.monticker.quant.persistence

import com.monticker.api.analytics.domain.DetectedPattern
import com.monticker.api.analytics.domain.PortfolioOptimization
import com.monticker.api.analytics.domain.TaxHarvestingLog
import com.monticker.api.quant.domain.QuantBacktestResult
import com.monticker.quant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * api 모듈의 LedgerEventPersistenceIntegrationTest에서 발견한 결함의 quant-engine 쪽 사례:
 * `columnDefinition = "jsonb"`만 있고 `@JdbcTypeCode(SqlTypes.JSON)`이 없는 String 컬럼은 Hibernate 6가
 * varchar로 바인딩해 Postgres가 INSERT를 거부한다("column X is of type jsonb but expression is of type
 * character varying") — null이어도. QuantBacktestResult(RuleSetService.runBacktest),
 * PortfolioOptimization(PortfolioOptimizerService), DetectedPattern(PatternRecognizerService),
 * TaxHarvestingLog(TaxOptimizerService)는 전부 실제 repository.save() 경로가 있다. mock 기반 단위테스트는
 * 이 결함을 못 본다 — 실제 Hibernate 매핑으로 실제(api가 마이그레이션한) 스키마에 써 본다.
 *
 * RuleSet은 여기 없다 — rule_sets 테이블은 V22에서 MongoDB로 이전되며 DROP됐고, RuleSet은 이제
 * api 사본과 같은 plain class다(RuleSetDocument/RuleSetRepository가 실제 저장소).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonbColumnPersistenceIntegrationTest : PostgresIntegrationTest() {

    private lateinit var sessionFactory: SessionFactory

    @BeforeAll
    fun buildSessionFactory() {
        jdbcTemplate.execute("SELECT 1")   // 베이스의 lazy dataSource가 Flyway 마이그레이션을 먼저 돌리게 한다 — validate가 그 스키마를 본다
        sessionFactory = Configuration()
            .addAnnotatedClass(QuantBacktestResult::class.java)
            .addAnnotatedClass(PortfolioOptimization::class.java)
            .addAnnotatedClass(DetectedPattern::class.java)
            .addAnnotatedClass(TaxHarvestingLog::class.java)
            .setProperty("hibernate.connection.url", postgres.jdbcUrl)
            .setProperty("hibernate.connection.username", postgres.username)
            .setProperty("hibernate.connection.password", postgres.password)
            .setProperty("hibernate.hbm2ddl.auto", "validate")
            .buildSessionFactory()
    }

    @AfterAll
    fun close() = sessionFactory.close()

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "jsonb-${System.nanoTime()}@test.local", "jsonb",
    )!!

    private fun newStock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "J${System.nanoTime() % 100_000_000}", "jsonb",
    )!!

    private fun typeAndText(table: String, column: String, where: String, arg: Any): Pair<String, String?> {
        val row = jdbcTemplate.queryForMap("SELECT pg_typeof($column)::text AS t, $column::text AS j FROM $table WHERE $where = ?", arg)
        return row["t"] as String to row["j"] as String?
    }

    @Test
    fun `a backtest result with json trades and equity curve is inserted through the JPA mapping`() {
        val stockId = newStock()
        val ruleSetId = "rs${System.nanoTime()}".take(24)

        sessionFactory.inTransaction { s ->
            s.persist(QuantBacktestResult(
                ruleSetId = ruleSetId, ruleSetVersion = 1, stockId = stockId,
                startDate = LocalDate.of(2025, 1, 2), endDate = LocalDate.of(2025, 6, 30),
                initialCapital = BigDecimal("10000000"), finalCapital = BigDecimal("10850000"),
                reliabilityScore = "B", reliabilityNotes = """["표본 거래 수 적음"]""",
                tradesJson = """[{"side":"BUY","qty":10}]""", equityCurveJson = """[[0,10000000],[1,10850000]]""",
            ))
        }

        val (type, text) = typeAndText("quant_backtest_results", "trades_json", "rule_set_id", ruleSetId)
        assertThat(type).isEqualTo("jsonb")
        assertThat(text).contains("\"side\"")
    }

    @Test
    fun `a backtest result with null json columns is inserted too — the null used to be bound as varchar`() {
        val stockId = newStock()
        val ruleSetId = "rs${System.nanoTime()}".take(24)

        sessionFactory.inTransaction { s ->
            s.persist(QuantBacktestResult(
                ruleSetId = ruleSetId, ruleSetVersion = 1, stockId = stockId,
                startDate = LocalDate.of(2025, 1, 2), endDate = LocalDate.of(2025, 6, 30),
                initialCapital = BigDecimal("10000000"), finalCapital = BigDecimal("9500000"),
            ))
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM quant_backtest_results WHERE rule_set_id = ?", Long::class.java, ruleSetId)).isEqualTo(1L)
    }

    @Test
    fun `a portfolio optimization with json universe, weights and frontier is inserted through the JPA mapping`() {
        val userId = newUser()

        sessionFactory.inTransaction { s ->
            s.persist(PortfolioOptimization(
                userId = userId, targetReturn = BigDecimal("0.08"),
                universeJson = """[1,2,3]""", weightsJson = """{"1":0.5,"2":0.3,"3":0.2}""",
                expectedReturn = BigDecimal("0.081"), expectedRisk = BigDecimal("0.12"),
                frontierJson = """[{"risk":0.1,"return":0.06}]""",
            ))
        }

        val (type, text) = typeAndText("portfolio_optimizations", "weights_json", "user_id", userId)
        assertThat(type).isEqualTo("jsonb")
        assertThat(text).contains("0.5")
    }

    @Test
    fun `a detected pattern with json swing points is inserted through the JPA mapping`() {
        val stockId = newStock()
        val now = Instant.now()

        sessionFactory.inTransaction { s ->
            s.persist(DetectedPattern(
                stockId = stockId, patternType = "DOUBLE_BOTTOM", confidenceScore = 77,
                swingPointsJson = """[{"idx":3,"price":1000},{"idx":9,"price":1200}]""",
                detectedAt = now, candleFrom = now.minusSeconds(3600), candleTo = now,
            ))
        }

        val (type, text) = typeAndText("detected_patterns", "swing_points_json", "stock_id", stockId)
        assertThat(type).isEqualTo("jsonb")
        assertThat(text).contains("\"price\"")
    }

    @Test
    fun `a tax harvesting log with json candidates is inserted through the JPA mapping`() {
        val userId = newUser()

        sessionFactory.inTransaction { s ->
            s.persist(TaxHarvestingLog(
                userId = userId, realizedGainYtd = BigDecimal("1500000"),
                candidatesJson = """[{"stockId":1,"unrealizedLoss":-30000}]""", estimatedTaxSaving = BigDecimal("6600"),
            ))
        }

        val (type, text) = typeAndText("tax_harvesting_logs", "candidates_json", "user_id", userId)
        assertThat(type).isEqualTo("jsonb")
        assertThat(text).contains("\"unrealizedLoss\"")
    }
}
