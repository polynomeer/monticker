package com.monticker.worker.alert

import com.monticker.worker.push.ExpoPushSender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mail.javamail.JavaMailSender
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * VOLUME_SURGE의 이전 SQL(GROUP BY 없이 비집계 컬럼과 AVG를 섞은 무효 쿼리)은 매번
 * Postgres 예외를 던졌지만 processAlert()의 바깥 try/catch가 조용히 삼켜 한 번도 발동한
 * 적이 없었다 — 기존 AlertEvaluatorTest는 jdbc.queryForMap을 직접 mock해서 이 문제를
 * 잡지 못했다. 이 테스트는 실제 Postgres에 candles_1d를 채워 넣고 실제 SQL을 실행해
 * (a) 더 이상 예외가 나지 않고 (b) 거래량 배율에 따라 실제로 트리거/미트리거되는지 검증한다.
 */
@Testcontainers
class AlertEvaluatorIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withDatabaseName("monticker")
                .withUsername("monticker")
                .withPassword("monticker")

        lateinit var jdbc: JdbcTemplate

        @JvmStatic
        @BeforeAll
        fun migrate() {
            val ds = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .apply { setDriverClassName(postgres.driverClassName) }
            Flyway.configure()
                .dataSource(ds)
                .locations("filesystem:../api/src/main/resources/db/migration")
                .load()
                .migrate()
            jdbc = JdbcTemplate(ds)
        }
    }

    private lateinit var pushSender: ExpoPushSender
    private lateinit var mailSender: JavaMailSender
    private lateinit var redis: StringRedisTemplate
    private lateinit var evaluator: AlertEvaluator
    private var userId: Long = 0
    private var stockId: Long = 0

    @BeforeEach
    fun setUp() {
        jdbc.update("TRUNCATE alert_histories, alert_rules, candles_1d, stocks, users CASCADE")

        pushSender = mockk(relaxed = true)
        mailSender = mockk(relaxed = true)
        redis = mockk()
        val valueOps = mockk<ValueOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any()) } returns true

        evaluator = AlertEvaluator(jdbc, pushSender, mockk(relaxed = true), redis, mailSender)

        userId = jdbc.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "surge-test@example.com", "surge-tester",
        )!!
        stockId = jdbc.queryForObject(
            """INSERT INTO stocks (symbol, name, market, exchange, sector, country, currency, is_active)
               VALUES ('SURGE1', 'Surge Inc', 'KOSPI', 'KRX', 'IT', 'KR', 'KRW', true) RETURNING id""",
            Long::class.java,
        )!!
    }

    private fun insertRule(surgeRatio: Double = 2.0, period: Int = 20): Long = jdbc.queryForObject(
        """INSERT INTO alert_rules (user_id, stock_id, rule_type, condition_json)
           VALUES (?, ?, 'VOLUME_SURGE', ?::jsonb) RETURNING id""",
        Long::class.java,
        userId, stockId, """{"surgeRatio": $surgeRatio, "period": $period}""",
    )!!

    private fun insertDailyCandle(daysAgo: Long, volume: Long) {
        jdbc.update(
            """INSERT INTO candles_1d (stock_id, candle_time, open, high, low, close, volume)
               VALUES (?, ?, 100, 110, 90, 105, ?)""",
            stockId,
            Timestamp.from(Instant.now().minusSeconds(daysAgo * 86_400)),
            volume,
        )
    }

    @Test
    fun `실제 SQL이 예외 없이 실행되고, 오늘 거래량이 평균 대비 배율을 넘으면 발동한다`() {
        insertRule(surgeRatio = 2.0)
        // 과거 5일: 평균 10,000
        repeat(5) { insertDailyCandle(daysAgo = (it + 1).toLong(), volume = 10_000) }
        // 오늘: 30,000 (평균의 3배 > surgeRatio 2배)
        insertDailyCandle(daysAgo = 0, volume = 30_000)

        evaluator.processAlert(stockId, BigDecimal("105"))

        // 디바이스 토큰이 없으므로 이메일 폴백 경로로 빠진다 — 여기까지 도달했다는 것 자체가
        // VOLUME_SURGE SQL이 예외 없이 실행되고 조건이 true로 평가됐다는 뜻이다.
        verify(exactly = 1) { mailSender.send(any<org.springframework.mail.SimpleMailMessage>()) }
        val historyCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_histories WHERE stock_id = ?", Int::class.java, stockId,
        )
        assertThat(historyCount).isEqualTo(1)
    }

    @Test
    fun `오늘 거래량이 평균 대비 배율에 못 미치면 발동하지 않는다`() {
        insertRule(surgeRatio = 2.0)
        repeat(5) { insertDailyCandle(daysAgo = (it + 1).toLong(), volume = 10_000) }
        // 오늘: 15,000 (평균의 1.5배 < surgeRatio 2배)
        insertDailyCandle(daysAgo = 0, volume = 15_000)

        evaluator.processAlert(stockId, BigDecimal("105"))

        verify(exactly = 0) { mailSender.send(any<org.springframework.mail.SimpleMailMessage>()) }
        val historyCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_histories WHERE stock_id = ?", Int::class.java, stockId,
        )
        assertThat(historyCount).isEqualTo(0)
    }
}
