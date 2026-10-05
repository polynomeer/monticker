package com.monticker.api.watchrule.application

import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.matching.submit.MarketOrderResult
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleExecution
import com.monticker.api.watchrule.domain.WatchRuleExecutionStatus
import com.monticker.api.watchrule.domain.WatchRuleSide
import com.monticker.api.watchrule.events.StockEventDetectedEvent
import com.monticker.api.watchrule.infrastructure.WatchRuleExecutionRepository
import com.monticker.api.watchrule.infrastructure.WatchRuleRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-051 — 룰 발동 판정. "정확히 한 번 체결"의 DB 쪽 증명은
 * `WatchRuleIdempotencyIntegrationTest`(실제 Postgres, 동시 스레드)가 맡고,
 * 여기서는 분기와 기록 내용을 본다.
 */
class WatchRuleExecutorTest {

    private val ruleRepo = mockk<WatchRuleRepository>()
    private val execRepo = mockk<WatchRuleExecutionRepository>(relaxed = true)
    private val submitter = mockk<OrderSubmitter>()
    private lateinit var executor: WatchRuleExecutor

    private val userId = 7L
    private val stockId = 100L
    private val eventId = 4242L

    @BeforeEach
    fun setUp() {
        executor = WatchRuleExecutor(ruleRepo, execRepo, submitter, SimpleMeterRegistry())
        every { execRepo.existsByWatchRuleIdAndStockEventId(any(), any()) } returns false
        every { execRepo.existsSince(any(), any(), any()) } returns false
        // relaxed 목의 제네릭 save()는 Object를 돌려줘 캐스트가 터진다. 예전엔 onEvent가 그 예외까지 삼켜
        // 테스트가 통과했다 — 기록 뒤의 메트릭 증가는 한 번도 실행되지 않았다.
        every { execRepo.save(any<WatchRuleExecution>()) } answers { firstArg() }
    }

    private fun rule(
        id: Long = 1L,
        side: WatchRuleSide = WatchRuleSide.BUY,
        quantity: Int = 10,
        minImportance: Int = 0,
        cooldownSec: Int = 600,
    ) = WatchRule(
        id = id, userId = userId, stockId = stockId, eventType = "VOLUME_SURGE",
        side = side, quantity = quantity, minImportanceScore = minImportance, cooldownSec = cooldownSec,
    )

    private fun event(importance: Int = 80) = StockEventDetectedEvent(
        eventId = eventId, stockId = stockId, eventType = "VOLUME_SURGE",
        importanceScore = importance, eventTimeMillis = Instant.EPOCH.toEpochMilli(),
    )

    private fun fill(orderId: Long = 900L) = MarketOrderResult(
        orderId = orderId, fillId = 1L, stockId = stockId, side = "BUY", quantity = 10,
        fillPrice = BigDecimal("1000"), amount = BigDecimal("10000"), filledAt = Instant.EPOCH,
    )

    private fun givenRules(vararg rules: WatchRule) {
        every { ruleRepo.findAllByStockIdAndEventTypeAndIsActiveTrue(stockId, "VOLUME_SURGE") } returns rules.toList()
    }

    private fun savedExecution(): WatchRuleExecution {
        val slot = slot<WatchRuleExecution>()
        verify { execRepo.save(capture(slot)) }
        return slot.captured
    }

    @Test
    fun `a matching rule submits a market order carrying the idempotency key`() {
        givenRules(rule())
        every { submitter.submitMarket(userId, stockId, "BUY", 10, "WR:1:$eventId") } returns fill()

        executor.onEvent(event())

        verify { submitter.submitMarket(userId, stockId, "BUY", 10, "WR:1:$eventId") }
        val execution = savedExecution()
        assertThat(execution.status).isEqualTo(WatchRuleExecutionStatus.EXECUTED)
        assertThat(execution.orderId).isEqualTo(900L)
        assertThat(execution.quantity).isEqualTo(10)
    }

    // 장애 시나리오 1 — 같은 이벤트가 다시 배달됐다(아웃박스 at-least-once).
    @Test
    fun `an already recorded rule-event pair never reaches the order path`() {
        givenRules(rule())
        every { execRepo.existsByWatchRuleIdAndStockEventId(1L, eventId) } returns true

        executor.onEvent(event())

        verify(exactly = 0) { submitter.submitMarket(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { execRepo.save(any()) }
    }

    // 장애 시나리오 2 — 사전 조회를 두 스레드가 동시에 통과해 기록이 유니크 제약에 걸렸다.
    @Test
    fun `a unique violation while recording is swallowed as normal concurrent behaviour`() {
        givenRules(rule())
        every { submitter.submitMarket(any(), any(), any(), any(), any()) } returns fill()
        every { execRepo.save(any()) } throws DataIntegrityViolationException("duplicate key")

        executor.onEvent(event())   // 예외가 새어나가면 컨슈머가 무한 재시도한다
    }

    @Test
    fun `an event below the rule's importance floor is skipped with a reason`() {
        givenRules(rule(minImportance = 90))

        executor.onEvent(event(importance = 80))

        verify(exactly = 0) { submitter.submitMarket(any(), any(), any(), any(), any()) }
        val execution = savedExecution()
        assertThat(execution.status).isEqualTo(WatchRuleExecutionStatus.SKIPPED)
        assertThat(execution.reason).contains("중요도")
    }

    @Test
    fun `a rule that fired inside its cooldown window is skipped`() {
        givenRules(rule(cooldownSec = 600))
        every { execRepo.existsSince(1L, WatchRuleExecutionStatus.EXECUTED, any()) } returns true

        executor.onEvent(event())

        verify(exactly = 0) { submitter.submitMarket(any(), any(), any(), any(), any()) }
        assertThat(savedExecution().status).isEqualTo(WatchRuleExecutionStatus.SKIPPED)
    }

    @Test
    fun `a zero cooldown never queries the cooldown window`() {
        givenRules(rule(cooldownSec = 0))
        every { submitter.submitMarket(any(), any(), any(), any(), any()) } returns fill()

        executor.onEvent(event())

        verify(exactly = 0) { execRepo.existsSince(any(), any(), any()) }
    }

    // 장애 시나리오 3 — 리스크 게이트가 막았다. 기록은 남고 예외는 새어나가지 않는다.
    @Test
    fun `a risk limit rejection is recorded and not rethrown`() {
        givenRules(rule())
        every { submitter.submitMarket(any(), any(), any(), any(), any()) } throws RiskLimitException("ConcentrationRule")

        executor.onEvent(event())

        val execution = savedExecution()
        assertThat(execution.status).isEqualTo(WatchRuleExecutionStatus.REJECTED)
        assertThat(execution.reason).contains("리스크 한도")
    }

    // 장애 시나리오 4 — 잔고/보유수량 부족(사가의 require 위반).
    @Test
    fun `an insufficient balance rejection is recorded`() {
        givenRules(rule())
        every { submitter.submitMarket(any(), any(), any(), any(), any()) } throws
            IllegalArgumentException("잔고 부족: 필요 10000")

        executor.onEvent(event())

        val execution = savedExecution()
        assertThat(execution.status).isEqualTo(WatchRuleExecutionStatus.REJECTED)
        assertThat(execution.reason).contains("잔고 부족")
    }

    // 장애 시나리오 5 — 현재가 없음 등으로 시장가가 체결되지 않았다.
    @Test
    fun `an unfilled market order is recorded as rejected`() {
        givenRules(rule())
        every { submitter.submitMarket(any(), any(), any(), any(), any()) } throws
            IllegalStateException("시장가 주문이 체결되지 않았습니다: orderId=1")

        executor.onEvent(event())

        assertThat(savedExecution().status).isEqualTo(WatchRuleExecutionStatus.REJECTED)
    }

    // 장애 시나리오 6 — 인프라 장애는 거부로 기록하지 않는다. 기록해버리면 멱등 키가 잡혀
    // 재시도가 영원히 막히고, 사용자에게는 "리스크로 거부됨"처럼 보인다.
    @Test
    fun `an infrastructure failure is not recorded so the consumer can retry`() {
        val executorRule = rule()
        givenRules(executorRule)
        every { submitter.submitMarket(any(), any(), any(), any(), any()) } throws
            org.springframework.dao.QueryTimeoutException("connection pool exhausted")

        // 컨슈머까지 던져야 @RetryableTopic 재시도·DLT가 동작한다
        assertThatThrownBy { executor.onEvent(event()) }
            .isInstanceOf(org.springframework.dao.QueryTimeoutException::class.java)

        verify(exactly = 0) { execRepo.save(any()) }
    }

    // 장애 시나리오 7 — 한 사용자의 룰이 실패해도 같은 이벤트의 다른 사용자 룰은 처리된다.
    @Test
    fun `one failing rule does not stop the other rules bound to the same event`() {
        val failing = rule(id = 1L)
        val healthy = rule(id = 2L)
        givenRules(failing, healthy)
        every { submitter.submitMarket(userId, stockId, "BUY", 10, "WR:1:$eventId") } throws
            RuntimeException("boom")
        every { submitter.submitMarket(userId, stockId, "BUY", 10, "WR:2:$eventId") } returns fill(orderId = 901L)

        // 실패한 룰 때문에 재시도로 가더라도, 그 전에 같은 이벤트의 나머지 룰은 처리를 마친다
        assertThatThrownBy { executor.onEvent(event()) }.hasMessage("boom")

        verify { submitter.submitMarket(userId, stockId, "BUY", 10, "WR:2:$eventId") }
    }

    // 장애 시나리오 8 — 주문은 체결됐는데 기록 직전에 프로세스가 죽었다. 재전달되면 멱등 키 덕분에
    // 주문은 새로 나가지 않고(submitMarket 이 첫 체결을 replay 한다) 기록만 채워진다.
    @Test
    fun `a crash between fill and record leaves the replayed fill recorded once`() {
        givenRules(rule())
        // 재기동 후: 기록이 없으니 사전 조회는 통과하고, 주문 제출은 첫 체결을 그대로 돌려준다.
        every { execRepo.existsByWatchRuleIdAndStockEventId(1L, eventId) } returns false
        every { submitter.submitMarket(userId, stockId, "BUY", 10, "WR:1:$eventId") } returns fill(orderId = 900L)

        executor.onEvent(event())

        val execution = savedExecution()
        assertThat(execution.status).isEqualTo(WatchRuleExecutionStatus.EXECUTED)
        assertThat(execution.orderId).isEqualTo(900L)
        // 주문 제출은 한 번만 호출된다 — 중복 체결 여부는 제출 쪽 멱등 키가 책임진다
        // (MatchingServiceTest 의 replay 테스트와 WatchRuleIdempotencyIntegrationTest 가 증명).
        verify(exactly = 1) { submitter.submitMarket(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an event with no matching rule touches nothing`() {
        givenRules()

        executor.onEvent(event())

        verify(exactly = 0) { submitter.submitMarket(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { execRepo.save(any()) }
    }

    @Test
    fun `the idempotency key is stable for a rule-event pair`() {
        assertThat(WatchRuleExecutor.idempotencyKey(12L, 34L)).isEqualTo("WR:12:34")
        // orders.idempotency_key 는 VARCHAR(100) — 키가 잘리면 서로 다른 발동이 같은 키가 된다.
        assertThat(WatchRuleExecutor.idempotencyKey(Long.MAX_VALUE, Long.MAX_VALUE)).hasSizeLessThan(100)
    }

    @Test
    fun `a SELL rule submits the sell side`() {
        givenRules(rule(side = WatchRuleSide.SELL))
        every { submitter.submitMarket(userId, stockId, "SELL", 10, any()) } returns fill()

        executor.onEvent(event())

        verify { submitter.submitMarket(userId, stockId, "SELL", 10, "WR:1:$eventId") }
    }
}
