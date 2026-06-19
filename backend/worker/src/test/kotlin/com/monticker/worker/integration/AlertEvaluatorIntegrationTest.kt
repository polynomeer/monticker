package com.monticker.worker.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.alert.AlertEvaluator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant

@Testcontainers
class AlertEvaluatorIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("postgres:16-alpine")
        ).withDatabaseName("monticker_test")
    }

    private lateinit var jdbc: JdbcTemplate
    private lateinit var evaluator: AlertEvaluator

    @BeforeEach
    fun setup() {
        val ds = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url      = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        jdbc = JdbcTemplate(ds)

        // minimal schema — no TimescaleDB extension needed for unit-level integration
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS stocks (
                id BIGSERIAL PRIMARY KEY, symbol VARCHAR(20), name VARCHAR(200), market VARCHAR(20)
            )
        """)
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGSERIAL PRIMARY KEY, email VARCHAR(200), created_at TIMESTAMPTZ DEFAULT now()
            )
        """)
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS alert_rules (
                id             BIGSERIAL PRIMARY KEY,
                user_id        BIGINT NOT NULL,
                stock_id       BIGINT,
                rule_type      VARCHAR(50) NOT NULL,
                condition_json TEXT NOT NULL,
                is_active      BOOLEAN NOT NULL DEFAULT true,
                created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
            )
        """)
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS alert_histories (
                id              BIGSERIAL PRIMARY KEY,
                rule_id         BIGINT NOT NULL,
                stock_id        BIGINT,
                triggered_at    TIMESTAMPTZ NOT NULL,
                message         TEXT NOT NULL,
                payload_json    TEXT,
                delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
            )
        """)
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS candles_1m (
                stock_id    BIGINT NOT NULL,
                candle_time TIMESTAMPTZ NOT NULL,
                open        NUMERIC(18,4),
                high        NUMERIC(18,4),
                low         NUMERIC(18,4),
                close       NUMERIC(18,4),
                volume      BIGINT
            )
        """)

        evaluator = AlertEvaluator(jdbc, ObjectMapper())
    }

    @BeforeEach
    fun cleanTables() {
        // order matters for FK constraints if added later
        if (::jdbc.isInitialized) {
            jdbc.execute("DELETE FROM alert_histories")
            jdbc.execute("DELETE FROM alert_rules")
            jdbc.execute("DELETE FROM candles_1m")
        }
    }

    @Test
    fun `PRICE_ABOVE alert fires when candle close exceeds threshold`() {
        jdbc.update("INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume) VALUES (1, now(), 70000, 76000, 69000, 75500, 10000)")
        jdbc.update("INSERT INTO alert_rules (user_id, stock_id, rule_type, condition_json) VALUES (1, 1, 'PRICE_ABOVE', '{\"threshold\": 75000}')")

        evaluator.evaluate()

        val count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_histories", Int::class.java)
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `duplicate alert suppressed within 10-minute cooldown`() {
        jdbc.update("INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume) VALUES (1, now(), 70000, 76000, 69000, 75500, 10000)")
        val ruleId = jdbc.queryForObject(
            "INSERT INTO alert_rules (user_id, stock_id, rule_type, condition_json) VALUES (1, 1, 'PRICE_ABOVE', '{\"threshold\": 75000}') RETURNING id",
            Long::class.java,
        )!!
        // simulate already-fired history
        jdbc.update(
            "INSERT INTO alert_histories (rule_id, stock_id, triggered_at, message) VALUES (?, 1, now(), 'prev')",
            ruleId,
        )

        evaluator.evaluate()

        val count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_histories", Int::class.java)
        assertThat(count).isEqualTo(1) // still 1 — no new row
    }

    @Test
    fun `PRICE_BELOW does not fire when price is above threshold`() {
        jdbc.update("INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume) VALUES (1, now(), 70000, 71000, 69000, 70500, 10000)")
        jdbc.update("INSERT INTO alert_rules (user_id, stock_id, rule_type, condition_json) VALUES (1, 1, 'PRICE_BELOW', '{\"threshold\": 60000}')")

        evaluator.evaluate()

        val count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_histories", Int::class.java)
        assertThat(count).isEqualTo(0)
    }
}
