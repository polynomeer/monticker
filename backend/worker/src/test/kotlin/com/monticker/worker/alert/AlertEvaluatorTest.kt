package com.monticker.worker.alert

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.monticker.worker.push.ExpoPushSender
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
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
    // ADR-042: 최종 상태 UPDATE + 색인 이벤트가 TransactionTemplate 안에서 실행된다 — 콜백을 그대로 통과시킨다
    private val tx = mockk<TransactionTemplate>().apply {
        every { execute(any<TransactionCallback<Any?>>()) } answers { firstArg<TransactionCallback<Any?>>().doInTransaction(mockk(relaxed = true)) }
    }
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val mailSender = mockk<JavaMailSender>(relaxed = true)
    private val meterRegistry = SimpleMeterRegistry()
    // 인덱스는 loadAll() 전이라 DB 폴백 경로 — 기존 테스트의 jdbc 스텁이 그대로 유효하다.
    // 지표 캐시는 TTL 0 — 테스트마다 다른 스텁이 들어가므로 캐시가 끼면 안 된다.
    private val dispatcher = AlertDispatcher(jdbc, pushSender, events, tx, redis, mailSender)
    private val evaluator = AlertEvaluator(AlertRuleIndex(jdbc, meterRegistry), IndicatorCache(jdbc, ttlMs = 0), InlineTriggerSink(dispatcher), meterRegistry)

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
        val published = slot<Any>()
        every { events.publishEvent(capture(published)) } returns Unit

        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[abc123]" }) }
        verify { jdbc.update(match<String> { it.contains("delivery_status") }, "SENT", 42L) }
        // ADR-042: ES 직접 저장 대신 최종 상태와 함께 색인 이벤트를 발행한다
        val ev = published.captured as com.monticker.worker.search.SearchIndexEvent
        assertThat(ev.index).isEqualTo("alert_histories")
        assertThat(ev.docId).isEqualTo("42")
        assertThat(ev.payload!!["ruleId"]).isEqualTo(1L)
        assertThat(ev.payload!!["deliveryStatus"]).isEqualTo("SENT")
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
        every { jdbc.update(match<String> { it.contains("delivery_status") }, "EMAIL_FALLBACK", 99L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("55000"))

        verify(exactly = 0) { pushSender.send(any()) }
        verify(exactly = 1) {
            mailSender.send(match<SimpleMailMessage> { it.to?.contains("user20@example.com") == true })
        }
        verify { jdbc.update(match<String> { it.contains("delivery_status") }, "EMAIL_FALLBACK", 99L) }
        // ADR-042: 이전엔 이메일 폴백 경로는 ES에 색인되지 않았다 — 이제 최종 상태와 함께 이벤트가 나간다
        verify { events.publishEvent(match<Any> { it is com.monticker.worker.search.SearchIndexEvent && it.payload!!["deliveryStatus"] == "EMAIL_FALLBACK" }) }
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

    /** 계속 하락하는 종가 시퀀스 — RSI가 낮게(과매도) 나온다. */
    private val decliningCloses = listOf(100.0, 98.0, 96.0, 94.0, 92.0, 90.0, 88.0, 86.0, 84.0, 82.0, 80.0, 78.0, 76.0, 74.0, 72.0)
    /** 계속 상승하는 종가 시퀀스 — RSI가 높게(과매수) 나온다. */
    private val risingCloses = decliningCloses.reversed()

    @Test
    fun `RSI_BELOW 과매도 조건 충족이면 push를 보낸다`() {
        val rule = AlertRuleRow(
            id            = 9L,
            userId        = 90L,
            stockId       = 5L,
            ruleType      = "RSI_BELOW",
            conditionJson = """{"period": 14, "threshold": 30}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        // fetchRsi는 DESC로 가져와 뒤집으므로 DB가 최신순으로 내려주는 그대로 반환
        every { jdbc.query(any<String>(), any<RowMapper<Double>>(), 5L, 42) } returns decliningCloses.reversed()
        stubCooldownAcquired(true)
        stubHistoryInsert(91L)
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns listOf("ExponentPushToken[rsi]")
        every { jdbc.update(any<String>(), any(), 91L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("72"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[rsi]" }) }
    }

    @Test
    fun `RSI_ABOVE 과매수 조건 미충족(하락장이라 RSI가 낮음)이면 push를 보내지 않는다`() {
        val rule = AlertRuleRow(
            id            = 10L,
            userId        = 100L,
            stockId       = 5L,
            ruleType      = "RSI_ABOVE",
            conditionJson = """{"period": 14, "threshold": 70}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.query(any<String>(), any<RowMapper<Double>>(), 5L, 42) } returns decliningCloses.reversed()

        evaluator.processAlert(stockId = 5L, price = BigDecimal("72"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `RSI_ABOVE 과매수 조건 충족(상승장)이면 push를 보낸다`() {
        val rule = AlertRuleRow(
            id            = 11L,
            userId        = 110L,
            stockId       = 5L,
            ruleType      = "RSI_ABOVE",
            conditionJson = """{"period": 14, "threshold": 70}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.query(any<String>(), any<RowMapper<Double>>(), 5L, 42) } returns risingCloses.reversed()
        stubCooldownAcquired(true)
        stubHistoryInsert(111L)
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns listOf("ExponentPushToken[rsi2]")
        every { jdbc.update(any<String>(), any(), 111L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("102"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[rsi2]" }) }
    }

    @Test
    fun `RSI 계산에 필요한 캔들 수가 부족하면 발동하지 않는다`() {
        val rule = AlertRuleRow(
            id            = 12L,
            userId        = 120L,
            stockId       = 5L,
            ruleType      = "RSI_BELOW",
            conditionJson = """{"period": 14, "threshold": 30}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.query(any<String>(), any<RowMapper<Double>>(), 5L, 42) } returns listOf(100.0, 99.0)

        evaluator.processAlert(stockId = 5L, price = BigDecimal("72"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `PRICE_BELOW_MA 조건 충족(현재가가 이동평균 아래)이면 push를 보낸다`() {
        val rule = AlertRuleRow(
            id            = 13L,
            userId        = 130L,
            stockId       = 5L,
            ruleType      = "PRICE_BELOW_MA",
            conditionJson = """{"period": 20}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.queryForObject(any<String>(), Double::class.java, 5L, 20) } returns 80000.0
        stubCooldownAcquired(true)
        stubHistoryInsert(131L)
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns listOf("ExponentPushToken[ma]")
        every { jdbc.update(any<String>(), any(), 131L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("70000"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[ma]" }) }
    }

    @Test
    fun `PRICE_ABOVE_MA 조건 미충족(현재가가 이동평균 아래)이면 push를 보내지 않는다`() {
        val rule = AlertRuleRow(
            id            = 14L,
            userId        = 140L,
            stockId       = 5L,
            ruleType      = "PRICE_ABOVE_MA",
            conditionJson = """{"period": 20}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.queryForObject(any<String>(), Double::class.java, 5L, 20) } returns 80000.0

        evaluator.processAlert(stockId = 5L, price = BigDecimal("70000"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `HOLDING_DROP 보유 포지션이 기준 하락률을 넘으면 push를 보낸다`() {
        val rule = AlertRuleRow(
            id            = 15L,
            userId        = 150L,
            stockId       = 5L,
            ruleType      = "HOLDING_DROP",
            conditionJson = """{"dropPct": 10}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        // 평단 100,000원, 현재가 85,000원 → -15% 하락(기준 10% 초과)
        every { jdbc.query(any<String>(), any<RowMapper<BigDecimal>>(), 150L, 5L) } returns listOf(BigDecimal("100000"))
        stubCooldownAcquired(true)
        stubHistoryInsert(151L)
        every { jdbc.queryForList(any<String>(), String::class.java, rule.userId) } returns listOf("ExponentPushToken[hold]")
        every { jdbc.update(any<String>(), any(), 151L) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("85000"))

        verify(exactly = 1) { pushSender.send(match { it.size == 1 && it[0].to == "ExponentPushToken[hold]" }) }
    }

    @Test
    fun `HOLDING_DROP 보유 포지션이 없으면 push를 보내지 않는다`() {
        val rule = AlertRuleRow(
            id            = 16L,
            userId        = 160L,
            stockId       = 5L,
            ruleType      = "HOLDING_DROP",
            conditionJson = """{"dropPct": 10}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        every { jdbc.query(any<String>(), any<RowMapper<BigDecimal>>(), 160L, 5L) } returns emptyList()

        evaluator.processAlert(stockId = 5L, price = BigDecimal("85000"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    @Test
    fun `HOLDING_DROP 하락폭이 기준 미만이면 push를 보내지 않는다`() {
        val rule = AlertRuleRow(
            id            = 17L,
            userId        = 170L,
            stockId       = 5L,
            ruleType      = "HOLDING_DROP",
            conditionJson = """{"dropPct": 10}""",
        )
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(rule)
        // 평단 100,000원, 현재가 95,000원 → -5% 하락(기준 10% 미달)
        every { jdbc.query(any<String>(), any<RowMapper<BigDecimal>>(), 170L, 5L) } returns listOf(BigDecimal("100000"))

        evaluator.processAlert(stockId = 5L, price = BigDecimal("95000"))

        verify(exactly = 0) { pushSender.send(any()) }
    }

    // resilience-plan §E7 / P1-2 — 한 룰의 실패가 같은 종목의 다른 룰을 막지 않고, 실패는 카운터로 남는다.
    @Test
    fun `한 룰의 평가 예외가 다른 룰의 평가를 막지 않고 ruleType별 실패 카운터를 올린다`() {
        val broken = AlertRuleRow(id = 1L, userId = 10L, stockId = 5L,
            ruleType = "VOLUME_SURGE", conditionJson = """{"surgeRatio": 2.0}""")
        val healthy = AlertRuleRow(id = 2L, userId = 10L, stockId = 5L,
            ruleType = "PRICE_ABOVE", conditionJson = """{"threshold": 70000}""")
        every { jdbc.query(any<String>(), any<RowMapper<AlertRuleRow>>(), 5L) } returns listOf(broken, healthy)
        // VOLUME_SURGE의 집계 쿼리가 실패한다 (예: 무효 SQL — ADR-044가 기록한 실제 사고)
        every { jdbc.queryForMap(any<String>(), *anyVararg()) } throws RuntimeException("bad SQL")
        stubCooldownAcquired(true)
        stubHistoryInsert(7L)
        every { jdbc.queryForList(any<String>(), String::class.java, *anyVararg()) } returns listOf("ExponentPushToken[x]")
        every { jdbc.update(any<String>(), *anyVararg()) } returns 1

        evaluator.processAlert(stockId = 5L, price = BigDecimal("75000"))

        // 두 번째(정상) 룰은 그대로 발동했다
        verify(exactly = 1) { pushSender.send(any()) }
        assertThat(meterRegistry.counter("alert_rule_eval_failed_total", "ruleType", "VOLUME_SURGE").count())
            .isEqualTo(1.0)
        assertThat(meterRegistry.counter("alert_rule_eval_failed_total", "ruleType", "PRICE_ABOVE").count())
            .isEqualTo(0.0)
    }
}
