package com.monticker.api.watchrule.application

import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleExecution
import com.monticker.api.watchrule.domain.WatchRuleExecutionStatus
import com.monticker.api.watchrule.events.StockEventDetectedEvent
import com.monticker.api.watchrule.infrastructure.WatchRuleExecutionRepository
import com.monticker.api.watchrule.infrastructure.WatchRuleRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * ADR-051 — 탐지된 이벤트에 걸린 watch rule을 찾아 모의투자 주문을 낸다.
 *
 * **정확히 한 번 체결**이 이 클래스의 계약이다. 아웃박스는 at-least-once 이고 컨슈머 리밸런싱도
 * 재소비를 만드므로, 같은 (룰, 이벤트)가 여러 번 들어오는 것을 정상으로 보고 두 겹으로 막는다:
 *
 *  1. 주문 제출에 멱등 키 `WR:{ruleId}:{eventId}`를 넘긴다(ADR-051, V48). 두 번째 제출은 새 주문을
 *     만들지 않고 첫 체결을 그대로 돌려준다 — **이것이 중복 체결을 막는 유일한 장치다.**
 *  2. 발동 기록에 (watch_rule_id, stock_event_id) 유니크(V49)를 건다. 기록이 두 줄 생기지 않는다.
 *
 * 사전 조회(`existsBy…`)는 빠른 경로일 뿐 방어선이 아니다 — 두 컨슈머 스레드가 동시에 통과할 수 있다.
 * 그 경우에도 1·2의 DB 제약이 결과를 하나로 수렴시킨다.
 *
 * **트랜잭션을 이 메서드에 걸지 않는다.** 주문 제출은 자체 트랜잭션이고, 거부되면 그 트랜잭션이
 * 롤백된다. 바깥을 한 트랜잭션으로 묶으면 롤백 마킹 때문에 REJECTED 기록 자체가 저장되지 못한다 —
 * "왜 주문이 안 나갔는지"를 남기는 것이 이 기능의 요구사항이라 기록과 주문을 분리한다.
 */
@Service
class WatchRuleExecutor(
    private val ruleRepo: WatchRuleRepository,
    private val execRepo: WatchRuleExecutionRepository,
    private val orderSubmitter: OrderSubmitter,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 0으로 미리 등록 — 대시보드와 알람이 첫 발동 전에도 시계열을 볼 수 있다.
    private val executed = registry.counter("watch_rule_executions_total", "status", "executed")
    private val rejected = registry.counter("watch_rule_executions_total", "status", "rejected")
    private val skipped = registry.counter("watch_rule_executions_total", "status", "skipped")

    fun onEvent(event: StockEventDetectedEvent) {
        val rules = ruleRepo.findAllByStockIdAndEventTypeAndIsActiveTrue(event.stockId, event.eventType)
        if (rules.isEmpty()) return
        // 한 룰의 실패가 같은 이벤트에 걸린 다른 사용자의 룰을 막지 않는다 — 룰 단위로 격리해 끝까지 돈 뒤,
        // 인프라 예외가 있었으면 다시 던져 컨슈머 재시도(@RetryableTopic → DLT)로 보낸다. 재시도 때 이미 처리된
        // 룰은 (룰, 이벤트) 기록으로 건너뛰므로 실패한 룰만 다시 시도된다. 예전엔 여기서 삼켜 재시도도 DLT도
        // 일어나지 않았다(2026-10 설계 리뷰).
        var failure: Throwable? = null
        rules.forEach { rule ->
            runCatching { applyRule(rule, event) }.onFailure {
                log.error("watch rule 처리 실패 ruleId={} eventId={}", rule.id, event.eventId, it)
                failure?.addSuppressed(it) ?: run { failure = it }
            }
        }
        failure?.let { throw it }
    }

    private fun applyRule(rule: WatchRule, event: StockEventDetectedEvent) {
        // 빠른 경로 — 이미 처리된 조합이면 주문 시도 자체를 건너뛴다(정확성은 DB 제약이 보장한다).
        if (execRepo.existsByWatchRuleIdAndStockEventId(rule.id, event.eventId)) {
            log.debug("watch rule 중복 이벤트 무시 ruleId={} eventId={}", rule.id, event.eventId)
            return
        }

        if (!rule.acceptsImportance(event.importanceScore)) {
            record(rule, event, WatchRuleExecutionStatus.SKIPPED,
                reason = "중요도 ${event.importanceScore} < 하한 ${rule.minImportanceScore}")
            return
        }

        if (inCooldown(rule)) {
            record(rule, event, WatchRuleExecutionStatus.SKIPPED,
                reason = "쿨다운 ${rule.cooldownSec}초 이내 재발동")
            return
        }

        try {
            val result = orderSubmitter.submitMarket(
                userId = rule.userId,
                stockId = rule.stockId,
                side = rule.side.name,
                quantity = rule.quantity,
                idempotencyKey = idempotencyKey(rule.id, event.eventId),
            )
            record(rule, event, WatchRuleExecutionStatus.EXECUTED,
                orderId = result.orderId, fillPrice = result.fillPrice, quantity = result.quantity)
            log.info("watch rule 발동 ruleId={} eventId={} orderId={}", rule.id, event.eventId, result.orderId)
        } catch (e: RiskLimitException) {
            record(rule, event, WatchRuleExecutionStatus.REJECTED, reason = "리스크 한도: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // 잔고 부족·보유 수량 부족 등 사가의 사전 조건 위반.
            record(rule, event, WatchRuleExecutionStatus.REJECTED, reason = e.message ?: "주문 거부")
        } catch (e: IllegalStateException) {
            // 현재가 없음·시장가 미체결 등. 사용자 잘못이 아니지만 이 이벤트로는 체결되지 않았다.
            record(rule, event, WatchRuleExecutionStatus.REJECTED, reason = e.message ?: "주문 실패")
        }
        // 그 외(DB·네트워크 등 인프라 예외)는 삼키지 않는다 — 호출자를 거쳐 컨슈머 재시도로 간다.
        // 재시도해도 멱등 키 덕에 중복 체결은 없다.
    }

    private fun inCooldown(rule: WatchRule): Boolean {
        if (rule.cooldownSec <= 0) return false
        val since = Instant.now().minusSeconds(rule.cooldownSec.toLong())
        return execRepo.existsSince(rule.id, WatchRuleExecutionStatus.EXECUTED, since)
    }

    private fun record(
        rule: WatchRule,
        event: StockEventDetectedEvent,
        status: WatchRuleExecutionStatus,
        orderId: Long? = null,
        fillPrice: java.math.BigDecimal? = null,
        quantity: Int? = null,
        reason: String? = null,
    ) {
        try {
            execRepo.save(
                WatchRuleExecution(
                    watchRuleId = rule.id,
                    userId = rule.userId,
                    stockEventId = event.eventId,
                    status = status,
                    orderId = orderId,
                    fillPrice = fillPrice,
                    quantity = quantity,
                    reason = reason,
                )
            )
        } catch (e: DataIntegrityViolationException) {
            // (룰, 이벤트) 유니크 충돌 — 다른 스레드가 같은 이벤트를 먼저 기록했다. 정상 동작이다.
            log.debug("watch rule 기록 중복 무시 ruleId={} eventId={}", rule.id, event.eventId)
            return
        }
        when (status) {
            WatchRuleExecutionStatus.EXECUTED -> executed
            WatchRuleExecutionStatus.REJECTED -> rejected
            WatchRuleExecutionStatus.SKIPPED -> skipped
        }.increment()
    }

    companion object {
        /** 주문 테이블의 부분 유니크 인덱스(V48)에 들어가는 값. 100자 제한 안에 들어온다. */
        fun idempotencyKey(ruleId: Long, eventId: Long) = "WR:$ruleId:$eventId"
    }
}
