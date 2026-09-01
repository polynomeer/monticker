package com.monticker.worker.alert

import com.monticker.worker.push.ExpoPushSender
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import java.math.BigDecimal
import java.time.Duration

class AlertEvaluatorTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val pushSender = mockk<ExpoPushSender>(relaxed = true)
    private val esOps = mockk<ElasticsearchOperations>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val mailSender = mockk<JavaMailSender>(relaxed = true)
    private val evaluator = AlertEvaluator(jdbc, pushSender, esOps, redis, mailSender)

    @BeforeEach
    fun setup() {
        // 기본: 해당 stockId에 활성 규칙 없음
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), any()) } returns emptyList()
    }

    /** dispatchAlert가 쿨다운 체크에 쓰는 Redis SETNX 결과를 스텁한다. */
    private fun stubCooldownAcquired(acquired: Boolean = true) {
        every { redis.opsForValue().setIfAbsent(any(), any(), any<Duration>()) } returns acquired
    }

    private fun stubHistoryInsert(historyId: Long) {
        every {
            jdbc.queryForObject(match<String> { it.contains("RETURNING") }, Long::class.java, *anyVararg())
        } returns historyId
    }

    @Test
    fun `활성 규칙 없으면 push를 보내지 않는다`() {
        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `PRICE_ABOVE 조건 충족 + 디바이스 토큰 있으면 push를 보내고 ES에 SENT 상태로 기록한다`() {
        val rule = AlertRuleRow(
            id            = 1L,
            userId        = 10L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        stubCooldownAcquired(true)
        stubHistoryInsert(42L)
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns
            listOf("ExponentPushToken[abc123]")
        every { jdbc.update(any<String>(), any(), 42L) } returns 1
        val docSlot = slot<AlertHistoryDocument>()
        every { esOps.save(capture(docSlot)) } returns mockk(relaxed = true)

        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[abc123]" }) }
        verify { jdbc.update(match<String> { it.contains("delivery_status") }, "SENT", 42L) }
        assertThat(docSlot.captured.ruleId).isEqualTo(1L)
        assertThat(docSlot.captured.deliveryStatus).isEqualTo("SENT")
    }

    @Test
    fun `PRICE_ABOVE 가격이 임계값과 정확히 같으면(경계) 발동하지 않는다`() {
        val rule = AlertRuleRow(
            id            = 6L,
            userId        = 60L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 75000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)

        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 0) {
            jdbc.queryForObject(match<String> { it.contains("RETURNING") }, Long::class.java, *anyVararg())
        }
    }

    @Test
    fun `디바이스 토큰 없으면 push 대신 이메일 폴백을 보낸다`() {
        val rule = AlertRuleRow(
            id            = 2L,
            userId        = 20L,
            stockId       = 5L,
            ruleType      = "PRICE_BELOW",
            conditionJson = """{"threshold": 60000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        stubCooldownAcquired(true)
        stubHistoryInsert(99L)
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns emptyList()
        every {
            jdbc.queryForObject(match<String> { it.contains("SELECT email") }, String::class.java, rule.userId)
        } returns "user20@example.com"
        every { jdbc.update(match<String> { it.contains("EMAIL_FALLBACK") }, 99L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("55000"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 1) {
            mailSender.send(match<SimpleMailMessage> { it.to?.contains("user20@example.com") == true })
        }
        verify { jdbc.update(match<String> { it.contains("EMAIL_FALLBACK") }, 99L) }
    }

    @Test
    fun `10분 이내 이미 발송된 경우(쿨다운) 중복 발송하지 않는다`() {
        val rule = AlertRuleRow(
            id            = 3L,
            userId        = 30L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 70000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        // Redis 쿨다운 키가 이미 존재 → setIfAbsent가 false를 반환한다
        stubCooldownAcquired(false)

        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 0) {
            jdbc.queryForObject(match<String> { it.contains("RETURNING") }, Long::class.java, *anyVararg())
        }
    }

    @Test
    fun `PRICE_ABOVE 조건 미충족이면 아무 것도 하지 않는다`() {
        val rule = AlertRuleRow(
            id            = 4L,
            userId        = 40L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE",
            conditionJson = """{"threshold": 80000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)

        // 현재가(75000) < 임계값(80000) → 발동 안 됨
        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 0) { redis.opsForValue().setIfAbsent(any(), any(), any<Duration>()) }
    }

    @Test
    fun `PRICE_BELOW 조건 미충족이면 알림을 발생시키지 않는다`() {
        val rule = AlertRuleRow(
            id            = 5L,
            userId        = 50L,
            stockId       = 5L,
            ruleType      = "PRICE_BELOW",
            conditionJson = """{"threshold": 60000}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)

        // 현재가(70500) > 임계값(60000) → PRICE_BELOW 미발동
        evaluator.processAlert(stockId = 5L, price = BigDecimal("70500"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 0) { redis.opsForValue().setIfAbsent(any(), any(), any<Duration>()) }
    }

    @Test
    fun `VOLUME_SURGE 조건 충족(평균 거래량 대비 배율 초과)이면 push를 보낸다`() {
        val rule = AlertRuleRow(
            id            = 7L,
            userId        = 70L,
            stockId       = 5L,
            ruleType      = "VOLUME_SURGE",
            conditionJson = """{"surgeRatio": 2.0, "period": 20}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        // 오늘 거래량 100,000 vs 평균 20,000 → 5배(임계 2배 초과)
        every { jdbc.queryForMap(any<String>(), 5L, 5L) } returns mapOf("today_vol" to 100_000L, "avg_vol" to 20_000.0)
        stubCooldownAcquired(true)
        stubHistoryInsert(77L)
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns
            listOf("ExponentPushToken[vol]")
        every { jdbc.update(any<String>(), any(), 77L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("71000"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[vol]" }) }
    }

    @Test
    fun `VOLUME_SURGE 조건 미충족(배율 이하)이면 push를 보내지 않는다`() {
        val rule = AlertRuleRow(
            id            = 8L,
            userId        = 80L,
            stockId       = 5L,
            ruleType      = "VOLUME_SURGE",
            conditionJson = """{"surgeRatio": 2.0, "period": 20}""",
        )

        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        // 오늘 거래량 50,000 vs 평균 30,000 → 1.67배(임계 2배 미만)
        every { jdbc.queryForMap(any<String>(), 5L, 5L) } returns mapOf("today_vol" to 50_000L, "avg_vol" to 30_000.0)

        evaluator.processAlert(stockId = 5L, price = BigDecimal("71000"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 0) { redis.opsForValue().setIfAbsent(any(), any(), any<Duration>()) }
    }
}
