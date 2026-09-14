package com.monticker.api.common

import com.monticker.api.analytics.domain.DetectedPattern
import com.monticker.api.analytics.domain.TaxHarvestingLog
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant

/**
 * JsonbColumnMappingTest(단위)가 애노테이션을 검사한다면, 이 테스트는 그 애노테이션이 실제로 INSERT를 통과시키는지
 * 실제 Hibernate 매핑 → 실제 Postgres로 확인한다. 원장(LedgerEventPersistenceIntegrationTest)과 같은 결함이
 * 있던 analytics 엔티티 두 개 — 둘 다 서비스가 save()로 쓴다(TaxOptimizerService, PatternRecognizerService).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonbEntityPersistenceIntegrationTest : PostgresIntegrationTest() {

    private lateinit var sessionFactory: SessionFactory

    @BeforeAll
    fun buildSessionFactory() {
        jdbcTemplate.execute("SELECT 1")   // lazy dataSource → Flyway 먼저
        sessionFactory = Configuration()
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

    @Test
    fun `a detected pattern with json swing points is inserted`() {
        val stockId = jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id LIMIT 1", Long::class.java)!!
        val now = Instant.now()

        sessionFactory.inTransaction { s ->
            s.persist(DetectedPattern(
                stockId = stockId, patternType = "DOUBLE_BOTTOM", confidenceScore = 80,
                swingPointsJson = """[{"t":1,"p":100},{"t":2,"p":90}]""", detectedAt = now, candleFrom = now, candleTo = now,
            ))
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT swing_points_json->1->>'p' FROM detected_patterns WHERE stock_id = ? ORDER BY id DESC LIMIT 1", String::class.java, stockId,
        )).isEqualTo("90")
    }

    @Test
    fun `a tax harvesting log with json candidates is inserted`() {
        val userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id", Long::class.java, "tax-${System.nanoTime()}@test.local", "tax",
        )!!

        sessionFactory.inTransaction { s ->
            s.persist(TaxHarvestingLog(userId = userId, realizedGainYtd = BigDecimal("1000"), candidatesJson = """[{"stockId":1}]"""))
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tax_harvesting_logs WHERE user_id = ?", Long::class.java, userId)).isEqualTo(1L)
    }
}
