package com.monticker.api.wallet

import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.wallet.domain.BehaviorScore
import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate

/**
 * ADR-043 라이브 검증에서 발견: `columnDefinition = "jsonb"`만 있는 String 컬럼을 Hibernate 6가 varchar로
 * 바인딩해 Postgres가 INSERT를 거부했다 — null이어도. 즉 원장 INSERT가 전부 실패하고 있었고, mock 단위테스트는
 * 이걸 절대 못 본다. 실제 Hibernate 매핑으로 실제 Postgres에 써 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerEventPersistenceIntegrationTest : PostgresIntegrationTest() {

    private lateinit var sessionFactory: SessionFactory

    @BeforeAll
    fun buildSessionFactory() {
        jdbcTemplate.execute("SELECT 1")   // 베이스의 lazy dataSource가 Flyway 마이그레이션을 먼저 돌리게 한다 — validate가 그 스키마를 본다
        sessionFactory = Configuration()
            .addAnnotatedClass(LedgerEvent::class.java)
            .addAnnotatedClass(BehaviorScore::class.java)
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

    @Test
    fun `a ledger event with json metadata is inserted through the JPA mapping`() {
        val userId = newUser()

        val id = sessionFactory.fromTransaction { s ->
            s.persist(LedgerEvent(
                userId = userId, eventType = LedgerEventType.SUBSCRIPTION_PAYMENT, amount = BigDecimal("-9900"),
                description = "구독료 결제", metadataJson = """{"paymentId":1,"planCode":"PRO"}""",
            ))
            s.flush()
            s.createQuery("select e.id from LedgerEvent e where e.userId = :u", Long::class.java).setParameter("u", userId).singleResult
        }

        val stored = jdbcTemplate.queryForMap("SELECT metadata_json::text AS m, pg_typeof(metadata_json)::text AS t FROM ledger_events WHERE id = ?", id)
        assertThat(stored["t"]).isEqualTo("jsonb")
        assertThat(stored["m"] as String).contains("\"planCode\"")
    }

    @Test
    fun `a ledger event with null metadata is inserted too — the null used to be bound as varchar`() {
        val userId = newUser()

        sessionFactory.inTransaction { s ->
            s.persist(LedgerEvent(userId = userId, eventType = LedgerEventType.FILL, amount = BigDecimal("-1"), paperTradeId = 424242L))
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_events WHERE user_id = ?", Long::class.java, userId)).isEqualTo(1L)
    }

    @Test
    fun `a behavior score with json breakdown is inserted through the JPA mapping`() {
        val userId = newUser()

        sessionFactory.inTransaction { s ->
            s.persist(BehaviorScore(
                userId = userId, scoreDate = LocalDate.now(), behaviorScore = 70, survivalScore = 80,
                scoreBreakdown = """{"a":1}""", feedbackJson = """["ok"]""",
            ))
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_behavior_scores WHERE user_id = ?", Long::class.java, userId)).isEqualTo(1L)
    }
}
