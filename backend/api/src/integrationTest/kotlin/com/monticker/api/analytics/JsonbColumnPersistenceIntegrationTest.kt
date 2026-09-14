package com.monticker.api.analytics

import com.monticker.api.analytics.domain.DetectedPattern
import com.monticker.api.analytics.domain.TaxHarvestingLog
import com.monticker.api.subscription.domain.PlanCode
import com.monticker.api.subscription.domain.SubscriptionPlan
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant

/**
 * LedgerEventPersistenceIntegrationTest와 같은 결함의 나머지 사례: `columnDefinition = "jsonb"`만 있고
 * `@JdbcTypeCode(SqlTypes.JSON)`이 없는 String 컬럼은 Hibernate 6가 varchar로 바인딩해 Postgres가
 * INSERT를 거부한다("column X is of type jsonb but expression is of type character varying").
 * DetectedPattern(PatternRecognizerService.save)·TaxHarvestingLog(TaxOptimizerService.save)는 실제
 * JPA 쓰기 경로가 있고, SubscriptionPlan은 V27 시드로만 채워지지만 매핑 자체는 같은 방식으로 검증한다.
 * mock 기반 단위테스트는 이 결함을 못 본다 — 실제 Hibernate 매핑으로 실제 Postgres에 써 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonbColumnPersistenceIntegrationTest : PostgresIntegrationTest() {

    private lateinit var sessionFactory: SessionFactory

    @BeforeAll
    fun buildSessionFactory() {
        jdbcTemplate.execute("SELECT 1")   // 베이스의 lazy dataSource가 Flyway 마이그레이션을 먼저 돌리게 한다 — validate가 그 스키마를 본다
        sessionFactory = Configuration()
            .addAnnotatedClass(DetectedPattern::class.java)
            .addAnnotatedClass(TaxHarvestingLog::class.java)
            .addAnnotatedClass(SubscriptionPlan::class.java)
            .setProperty("hibernate.connection.url", postgres.jdbcUrl)
            .setProperty("hibernate.connection.username", postgres.username)
            .setProperty("hibernate.connection.password", postgres.password)
            .setProperty("hibernate.hbm2ddl.auto", "validate")
            // SubscriptionPlan은 컬럼명을 명시하지 않고 Spring Boot 기본(camelCase → snake_case) 전략에 기댄다
            .setProperty("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy::class.java.name)
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

    @Test
    fun `a detected pattern with json swing points is inserted through the JPA mapping`() {
        val stockId = newStock()
        val now = Instant.now()

        sessionFactory.inTransaction { s ->
            s.persist(DetectedPattern(
                stockId = stockId, patternType = "HEAD_AND_SHOULDERS", confidenceScore = 82,
                swingPointsJson = """[{"idx":3,"price":1000},{"idx":9,"price":1200}]""",
                detectedAt = now, candleFrom = now.minusSeconds(3600), candleTo = now,
            ))
        }

        val stored = jdbcTemplate.queryForMap(
            "SELECT swing_points_json::text AS j, pg_typeof(swing_points_json)::text AS t FROM detected_patterns WHERE stock_id = ?", stockId,
        )
        assertThat(stored["t"]).isEqualTo("jsonb")
        assertThat(stored["j"] as String).contains("\"price\"")
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

        val stored = jdbcTemplate.queryForMap(
            "SELECT candidates_json::text AS j, pg_typeof(candidates_json)::text AS t FROM tax_harvesting_logs WHERE user_id = ?", userId,
        )
        assertThat(stored["t"]).isEqualTo("jsonb")
        assertThat(stored["j"] as String).contains("\"unrealizedLoss\"")
    }

    @Test
    fun `a subscription plan with json features is inserted through the JPA mapping`() {
        // 세 PlanCode 모두 V27 시드로 이미 존재하고 code는 UNIQUE라, 같은 트랜잭션 안에서 시드 행을 지우고
        // 다시 넣은 뒤 롤백한다 — 바인딩 검증만 하고 공유 시드는 건드리지 않는다.
        val session = sessionFactory.openSession()
        val tx = session.beginTransaction()
        try {
            session.createNativeMutationQuery("DELETE FROM subscription_plans WHERE code = 'QUANT'").executeUpdate()
            session.persist(SubscriptionPlan(
                code = PlanCode.QUANT, name = "Quant", price = BigDecimal("29900"),
                features = """["Quant Lab 전체","백테스트 우선 실행"]""",
            ))
            session.flush()

            // 아직 커밋 전이라 같은 커넥션으로 읽어야 보인다
            val stored = session.doReturningWork { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT features::text AS j, pg_typeof(features)::text AS t FROM subscription_plans WHERE code = 'QUANT'").use { rs ->
                        rs.next(); rs.getString("j") to rs.getString("t")
                    }
                }
            }
            assertThat(stored.second).isEqualTo("jsonb")
            assertThat(stored.first).contains("Quant Lab")
        } finally {
            tx.rollback()
            session.close()
        }
    }
}
