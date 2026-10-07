package com.monticker.api.batch

import com.monticker.api.brokerage.domain.BrokerageSettlement
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.paper.domain.PaperSettlement
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import com.monticker.api.subscription.domain.PaymentRecord
import com.monticker.api.subscription.domain.PaymentStatus
import com.monticker.api.subscription.domain.SubscriptionPlan
import com.monticker.api.subscription.domain.UserSubscription
import com.monticker.api.subscription.infrastructure.PaymentRecordRepository
import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import com.monticker.api.support.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.batch.item.ExecutionContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 2026-10 설계 리뷰 10a — 정산·결제 청소·구독 갱신 배치의 키셋 쿼리를 실제 Hibernate·Spring Data → 실제 Postgres로 확인한다.
 * 이 리더들은 예전에 "돌긴 도는데 못 읽는" 결함(ADR-053 NoSuchMethodException, 결제 청소의 Slice 캐스팅)을 단위 테스트가
 * 놓쳤던 경로라, 쿼리가 실제로 실행되고 처리로 줄어드는 집합을 끝까지 읽는지를 DB에서 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeysetBatchReaderIntegrationTest : PostgresIntegrationTest() {

    private lateinit var sessionFactory: SessionFactory
    private lateinit var em: EntityManager
    private val repos by lazy { JpaRepositoryFactory(em) }

    @BeforeAll
    fun setUp() {
        jdbcTemplate.execute("SELECT 1")   // lazy dataSource → Flyway 먼저
        sessionFactory = Configuration()
            .addAnnotatedClass(PaperSettlement::class.java)
            .addAnnotatedClass(PaperTrade::class.java)   // findUpcomingPendingDates(ADR-086)가 체결 시각을 조인해 읽는다
            .addAnnotatedClass(BrokerageSettlement::class.java)
            .addAnnotatedClass(PaymentRecord::class.java)
            .addAnnotatedClass(UserSubscription::class.java)
            .addAnnotatedClass(SubscriptionPlan::class.java)
            .setProperty("hibernate.connection.url", postgres.jdbcUrl)
            .setProperty("hibernate.connection.username", postgres.username)
            .setProperty("hibernate.connection.password", postgres.password)
            .buildSessionFactory()
        em = sessionFactory.createEntityManager()
    }

    @AfterAll
    fun close() {
        em.close()
        sessionFactory.close()
    }

    private fun user(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, 'k') RETURNING id", Long::class.java, "keyset-${System.nanoTime()}@test.local",
    )!!

    private fun plan(): Long = jdbcTemplate.queryForObject("SELECT id FROM subscription_plans ORDER BY id LIMIT 1", Long::class.java)!!

    @Test
    fun `실거래 정산 — 처리로 PENDING이 줄어도 한 실행에서 기준일 도래분을 전부 읽는다`() {
        val repo = repos.getRepository(BrokerageSettlementRepository::class.java)
        val today = LocalDate.of(2031, 1, 10)   // 다른 테스트의 행과 섞이지 않게 먼 미래 기준일
        jdbcTemplate.update("UPDATE brokerage_settlements SET status = 'SETTLED' WHERE status = 'PENDING'")
        val userId = user()
        val accountId = jdbcTemplate.queryForObject(
            "INSERT INTO brokerage_accounts (user_id, provider, account_number) VALUES (?, 'KIS', ?) RETURNING id",
            Long::class.java, userId, "K${System.nanoTime() % 100000000}",
        )!!
        fun settlement(settleDate: LocalDate, status: String = "PENDING"): Long = jdbcTemplate.queryForObject(
            """
            INSERT INTO brokerage_settlements (user_id, account_id, symbol, side, quantity, fill_price, gross_amount, net_amount, settle_date, status)
            VALUES (?, ?, 'X', 'BUY', 1, 1, 1, 1, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, accountId, Date.valueOf(settleDate), status,
        )!!
        val due = (1..120).map { settlement(today.minusDays((it % 3).toLong())) }
        settlement(today.plusDays(1))                     // 기준일 전 → 읽지 않는다
        settlement(today.minusDays(1), status = "SETTLED") // 이미 정산 → 읽지 않는다

        val reader = KeysetItemReader<BrokerageSettlement>("t", 50, BrokerageSettlement::id) { afterId, limit ->
            em.clear()
            repo.findDueSettlementsAfter(today, afterId, PageRequest.of(0, limit))
        }.apply { open(ExecutionContext()) }
        val read = mutableListOf<Long>()
        while (true) {
            val chunk = generateSequence { reader.read() }.take(50).map { it.id }.toList()
            if (chunk.isEmpty()) break
            read += chunk
            // 청크 커밋 = 정산 완료 → PENDING에서 빠진다. offset 페이징이면 여기서 다음 페이지가 50건 밀린다.
            jdbcTemplate.update("UPDATE brokerage_settlements SET status = 'SETTLED' WHERE id = ANY(?)", chunk.toTypedArray())
        }

        assertThat(read).containsExactlyElementsOf(due)
    }

    @Test
    fun `모의 정산 키셋 쿼리가 실행된다`() {
        val repo = repos.getRepository(PaperSettlementRepository::class.java)

        val page = repo.findDueSettlementsAfter(LocalDate.of(2031, 1, 10), Long.MAX_VALUE - 1, PageRequest.of(0, 50))

        assertThat(page).isEmpty()
    }

    @Test
    fun `결제 청소 — PG 주문번호 있는 오래된 PENDING만, afterId 이후 id 순으로`() {
        val repo = repos.getRepository(PaymentRecordRepository::class.java)
        val userId = user(); val planId = plan(); val now = Instant.now()
        fun payment(status: String, createdAt: Instant, pgOrderId: String? = "ord-${System.nanoTime()}"): Long = jdbcTemplate.queryForObject(
            "INSERT INTO payment_records (user_id, plan_id, amount, status, created_at, pg_order_id) VALUES (?, ?, 1000, ?, ?, ?) RETURNING id",
            Long::class.java, userId, planId, status, Timestamp.from(createdAt), pgOrderId,
        )!!
        val old = now.minus(Duration.ofHours(1))
        val a = payment("PENDING", old)
        payment("PENDING", now)                    // 아직 확정 중일 수 있다
        payment("PENDING", old, pgOrderId = null)  // PG에 되물을 열쇠가 없다
        payment("SUCCESS", old)
        val b = payment("PENDING", old)
        val before = now.minus(Duration.ofMinutes(10))

        val all = repo.findAllByStatusAndPgOrderIdIsNotNullAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(
            PaymentStatus.PENDING, before, a - 1, PageRequest.of(0, 20),
        ).map { it.id }
        val afterA = repo.findAllByStatusAndPgOrderIdIsNotNullAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(
            PaymentStatus.PENDING, before, a, PageRequest.of(0, 20),
        ).map { it.id }

        assertThat(all).containsExactly(a, b)
        assertThat(afterA).containsExactly(b)
    }

    @Test
    fun `구독 갱신 — 임계 전에 만료되는 ACTIVE만, afterId 이후 id 순으로`() {
        val repo = repos.getRepository(UserSubscriptionRepository::class.java)
        val planId = plan(); val threshold = Instant.parse("2031-01-10T00:00:00Z")
        fun subscription(status: String, expiresAt: Instant?): Long = jdbcTemplate.queryForObject(
            "INSERT INTO user_subscriptions (user_id, plan_id, status, expires_at) VALUES (?, ?, ?, ?) RETURNING id",
            Long::class.java, user(), planId, status, expiresAt?.let { Timestamp.from(it) },
        )!!
        val base = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM user_subscriptions", Long::class.java)!!
        val a = subscription("ACTIVE", threshold.minusSeconds(60))
        subscription("ACTIVE", threshold.plusSeconds(60))     // 아직 멀었다
        subscription("ACTIVE", null)                          // 무기한
        subscription("EXPIRED", threshold.minusSeconds(60))   // 이미 끝났다
        val b = subscription("ACTIVE", threshold.minusSeconds(1))

        val all = repo.findExpiringBeforeAfter(threshold, base, PageRequest.of(0, 20)).map { it.id }
        val afterA = repo.findExpiringBeforeAfter(threshold, a, PageRequest.of(0, 20)).map { it.id }

        assertThat(all).containsExactly(a, b)
        assertThat(afterA).containsExactly(b)
    }
}
