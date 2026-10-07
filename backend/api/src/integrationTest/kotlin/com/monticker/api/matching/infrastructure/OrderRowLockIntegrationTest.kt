package com.monticker.api.matching.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MatchingService.cancelOrder의 이중 환불 방지는 `findWithLockById`가 실제로 행 락(FOR UPDATE)을
 * 거는 데 전부 기대고 있다. 목 기반 단위 테스트로는 Spring Data가 @Lock을 쿼리에 반영하는지
 * 알 수 없으므로 실제 Postgres에서 "A가 잡은 동안 B가 기다린다"를 잰다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 각 스레드가 자기 트랜잭션을 연다
class OrderRowLockIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
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

    @Autowired private lateinit var orderRepo: OrderRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private fun insertPendingOrder(): Long {
        val userId = jdbc.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id", Long::class.java,
            "lock-${System.nanoTime()}@test.local", "lock",
        )!!
        val stockId = jdbc.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, 'lock', 'KOSPI', 'KRX') RETURNING id",
            Long::class.java, "L${System.nanoTime() % 100000000}",
        )!!
        return jdbc.queryForObject(
            """
            INSERT INTO orders (user_id, stock_id, side, order_type, quantity, limit_price, status)
            VALUES (?, ?, 'BUY', 'LIMIT', 10, 900, 'PENDING') RETURNING id
            """.trimIndent(),
            Long::class.java, userId, stockId,
        )!!
    }

    @Test
    fun `findWithLockById는 행을 잡는다 — 먼저 잡은 트랜잭션이 끝날 때까지 두 번째 조회가 기다린다`() {
        val orderId = insertPendingOrder()
        val tx = TransactionTemplate(txManager)
        val holderLocked = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val holdMillis = 1_000L

        try {
            val holder = pool.submit {
                tx.executeWithoutResult {
                    assertThat(orderRepo.findWithLockById(orderId)).isNotNull
                    holderLocked.countDown()
                    Thread.sleep(holdMillis)
                }
            }
            assertThat(holderLocked.await(10, TimeUnit.SECONDS)).isTrue()

            val waitedMillis = pool.submit<Long> {
                val start = System.nanoTime()
                tx.executeWithoutResult { orderRepo.findWithLockById(orderId) }
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            }.get(10, TimeUnit.SECONDS)
            holder.get(10, TimeUnit.SECONDS)

            // 락이 없으면 두 번째 조회는 수 ms 안에 끝난다. 잡혀 있었다면 남은 보유 시간만큼 기다린다.
            assertThat(waitedMillis).isGreaterThanOrEqualTo(holdMillis / 2)
        } finally {
            pool.shutdownNow()
        }
    }
}
