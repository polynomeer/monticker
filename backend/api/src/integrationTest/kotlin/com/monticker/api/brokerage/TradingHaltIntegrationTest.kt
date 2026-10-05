package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.HaltScope
import com.monticker.api.brokerage.application.TradingHaltService
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.support.PostgresIntegrationTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

/** ADR-057 — 킬 스위치의 저장·판정 규칙을 실제 Postgres(V52)에서 확인한다. */
class TradingHaltIntegrationTest : PostgresIntegrationTest() {

    private val registry = SimpleMeterRegistry()
    private val service by lazy { TradingHaltService(jdbcTemplate, registry) }

    // 컨테이너를 다른 통합 테스트와 공유하므로 활성 스위치를 남기지 않는다(남기면 그쪽 주문이 막힌다).
    @AfterEach fun liftAll() { jdbcTemplate.update("UPDATE trading_halts SET lifted_at = now(), lift_reason = 'test cleanup' WHERE lifted_at IS NULL") }

    private fun user(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, 'h') RETURNING id", Long::class.java, "h-${System.nanoTime()}@test.local",
    )!!

    @Test
    fun `범위 판정 — 전역은 모두, 증권사는 그 증권사만, 사용자는 그 사용자만 막는다`() {
        val alice = user(); val bob = user()
        assertThat(service.findActive(BrokerageProvider.KIS, alice)).isNull()

        val kis = service.halt(HaltScope.PROVIDER, "kis", "KIS 점검", adminId = null)
        assertThat(service.findActive(BrokerageProvider.KIS, alice)?.id).isEqualTo(kis.id)
        assertThat(service.findActive(BrokerageProvider.TOSS, alice)).isNull()

        val userHalt = service.halt(HaltScope.USER, alice.toString(), "조사", adminId = null)
        assertThat(service.findActive(BrokerageProvider.TOSS, alice)?.id).isEqualTo(userHalt.id)
        assertThat(service.findActive(BrokerageProvider.TOSS, bob)).isNull()

        // 여러 개가 걸리면 가장 넓은 범위를 보여준다
        val global = service.halt(HaltScope.GLOBAL, null, "전체 점검", adminId = null)
        assertThat(service.findActive(BrokerageProvider.KIS, alice)?.id).isEqualTo(global.id)
        assertThat(registry.find("trading_halt_active").tag("scope", "GLOBAL").gauge()?.value()).isEqualTo(1.0)
    }

    @Test
    fun `같은 범위에 활성 스위치는 하나 — 해제하면 다시 켤 수 있고 이력은 남는다`() {
        val first = service.halt(HaltScope.GLOBAL, null, "1차", adminId = null)
        assertThatThrownBy { service.halt(HaltScope.GLOBAL, null, "중복", adminId = null) }.isInstanceOf(BusinessRuleException::class.java)

        service.lift(first.id, "복구", adminId = null)
        assertThatThrownBy { service.lift(first.id, "두 번", adminId = null) }.isInstanceOf(BusinessRuleException::class.java)
        assertThat(service.findActive(BrokerageProvider.KIS, user())).isNull()

        val second = service.halt(HaltScope.GLOBAL, null, "2차", adminId = null)
        assertThat(second.id).isNotEqualTo(first.id)
        val history = service.list(activeOnly = false).filter { it.id == first.id || it.id == second.id }
        assertThat(history).hasSize(2)
        assertThat(history.single { it.id == first.id }.liftReason).isEqualTo("복구")
    }

    @Test
    fun `대상 검증 — 알 수 없는 증권사·없는 사용자·빈 사유는 거부`() {
        assertThatThrownBy { service.halt(HaltScope.PROVIDER, "MOCK", "x", null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.halt(HaltScope.USER, "999999999", "x", null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.halt(HaltScope.GLOBAL, "KIS", "x", null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.halt(HaltScope.GLOBAL, null, "  ", null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `DB 제약이 앱 검증을 우회한 SQL도 막는다`() {
        assertThatThrownBy { jdbcTemplate.update("INSERT INTO trading_halts (scope, target, reason) VALUES ('PROVIDER', 'MOCK', 'x')") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbcTemplate.update("INSERT INTO trading_halts (scope, target, reason) VALUES ('GLOBAL', 'KIS', 'x')") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        // 판정은 target = users.id::text — '007'은 영영 걸리지 않으므로 DB가 받지 않는다
        assertThatThrownBy { jdbcTemplate.update("INSERT INTO trading_halts (scope, target, reason) VALUES ('USER', '007', 'x')") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `비상 경로 — 앱을 거치지 않고 SQL 한 줄로 켜도 다음 판정부터 즉시 막힌다(캐시 없음)`() {
        val u = user()
        assertThat(service.findActive(BrokerageProvider.TOSS, u)).isNull()

        jdbcTemplate.update("INSERT INTO trading_halts (scope, reason) VALUES ('GLOBAL', '앱 다운 중 수동 정지')")

        val halt = service.findActive(BrokerageProvider.TOSS, u)
        assertThat(halt?.reason).isEqualTo("앱 다운 중 수동 정지")
        assertThat(halt?.haltedBy).isNull()
    }
}
