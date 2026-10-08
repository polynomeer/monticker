package com.monticker.api.watchrule.application

import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.matching.submit.OrderOrigin
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.quant.events.QuantSignalEmittedEvent
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
    private val guards: WatchRuleGuards,
    private val signalAccess: StrategySignalAccess,
    private val planner: WatchRuleOrderPlanner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 0으로 미리 등록 — 대시보드와 알람이 첫 발동 전에도 시계열을 볼 수 있다.
    private val executed = registry.counter("watch_rule_executions_total", "status", "executed")
    private val rejected = registry.counter("watch_rule_executions_total", "status", "rejected")
    private val skipped = registry.counter("watch_rule_executions_total", "status", "skipped")
    private val placedCounter = registry.counter("watch_rule_executions_total", "status", "placed")

    /** 발동 원인 — 탐지 이벤트 또는 퀀트 신호(ADR-077). 기록·멱등 키가 원인별로 갈린다. */
    private data class Trigger(
        /** 발동 종목 — 그룹 규칙(ADR-095)은 규칙에 종목이 없으므로 원인의 종목을 쓴다. */
        val stockId: Long,
        val stockEventId: Long?,
        val quantSignalId: Long?,
        val importanceScore: Int?,
        val time: Instant,
    ) {
        fun key(ruleId: Long) = if (stockEventId != null) idempotencyKey(ruleId, stockEventId) else signalIdempotencyKey(ruleId, quantSignalId!!)
        override fun toString() = stockEventId?.let { "event=$it" } ?: "signal=$quantSignalId"
    }

    /** ADR-077 — 포워드 테스트 신호. 이 종목·이 전략·이 방향의 활성 규칙만 발동한다. */
    fun onQuantSignal(event: QuantSignalEmittedEvent) {
        val rules = ruleRepo.findActiveForSignal(event.stockId, event.ruleSetId)
            .filter { it.signalDirection == event.direction }
        runAll(rules, Trigger(stockId = event.stockId, stockEventId = null, quantSignalId = event.signalId, importanceScore = null, time = event.signalTime))
    }

    fun onEvent(event: StockEventDetectedEvent) {
        // 종목 규칙 + 지금 이 종목이 든 내 관심종목 그룹 규칙(ADR-095). 그룹 구성은 평가 시점 기준이다.
        val rules = ruleRepo.findActiveForEvent(event.stockId, event.eventType)
        // eventTimeMillis가 빠진 와이어(기본값 0)는 수신 시각으로 — 복합 조건 창이 1970년을 보지 않게.
        val time = if (event.eventTimeMillis > 0) Instant.ofEpochMilli(event.eventTimeMillis) else Instant.now()
        runAll(rules, Trigger(event.stockId, event.eventId, null, event.importanceScore, time))
    }

    private fun runAll(rules: List<WatchRule>, trigger: Trigger) {
        if (rules.isEmpty()) return
        // 한 룰의 실패가 같은 이벤트에 걸린 다른 사용자의 룰을 막지 않는다 — 룰 단위로 격리해 끝까지 돈 뒤,
        // 인프라 예외가 있었으면 다시 던져 컨슈머 재시도(@RetryableTopic → DLT)로 보낸다. 재시도 때 이미 처리된
        // 룰은 (룰, 이벤트) 기록으로 건너뛰므로 실패한 룰만 다시 시도된다. 예전엔 여기서 삼켜 재시도도 DLT도
        // 일어나지 않았다(2026-10 설계 리뷰).
        var failure: Throwable? = null
        rules.forEach { rule ->
            runCatching { applyRule(rule, trigger) }.onFailure {
                log.error("watch rule 처리 실패 ruleId={} {}", rule.id, trigger, it)
                failure?.addSuppressed(it) ?: run { failure = it }
            }
        }
        failure?.let { throw it }
    }

    private fun applyRule(rule: WatchRule, trigger: Trigger) {
        // 빠른 경로 — 이미 처리된 조합이면 주문 시도 자체를 건너뛴다(정확성은 DB 제약이 보장한다).
        val seen = if (trigger.stockEventId != null) execRepo.existsByWatchRuleIdAndStockEventId(rule.id, trigger.stockEventId)
            else execRepo.existsByWatchRuleIdAndQuantSignalId(rule.id, trigger.quantSignalId!!)
        if (seen) {
            log.debug("watch rule 중복 발동 무시 ruleId={} {}", rule.id, trigger)
            return
        }

        if (trigger.importanceScore != null && !rule.acceptsImportance(trigger.importanceScore)) {
            record(rule, trigger, WatchRuleExecutionStatus.SKIPPED,
                reason = "중요도 ${trigger.importanceScore} < 하한 ${rule.minImportanceScore}")
            return
        }

        // ADR-035 — 구독을 끊었으면 전략 신호로 주문하지 않는다(등록 때 확인했어도 발동 때 다시 본다).
        if (rule.isQuantSignalRule && !signalAccess.canAccess(rule.userId, rule.ruleSetId!!)) {
            record(rule, trigger, WatchRuleExecutionStatus.SKIPPED, reason = "전략 신호 접근 권한 없음(구독 해지 등)")
            return
        }

        // ADR-077 복합 조건 — 주 원인 앞 창 안에 동반 이벤트가 모두 있었는가.
        val required = rule.requiredTypes()
        if (required.isNotEmpty()) {
            val missing = guards.missingRequiredEvents(trigger.stockId, required, trigger.time, rule.conditionWindowSec ?: DEFAULT_WINDOW_SEC)
            if (missing.isNotEmpty()) {
                record(rule, trigger, WatchRuleExecutionStatus.SKIPPED,
                    reason = "복합 조건 미충족: ${missing.joinToString()} 없음 (${(rule.conditionWindowSec ?: DEFAULT_WINDOW_SEC) / 60}분 내)")
                return
            }
        }

        // 쿨다운 + 하루 최대 발동(ADR-077) — 규칙 행을 잠근 한 트랜잭션에서 판정하고 발동을 기록한다(WatchRuleGuards).
        // 같은 규칙에 서로 다른 이벤트가 동시에 와도 하나만 여기를 통과한다. 주문이 나가지 않으면 돌려준다.
        // ADR-095 — 그룹 규칙도 **규칙 단위**다: 그룹 안 서로 다른 종목의 이벤트가 동시에 와도 쿨다운 안에서는 하나만 발동하고,
        // 하루 한도도 그룹 전체에 대한 횟수다(종목별 쿨다운이 아니다).
        val claim = when (val c = guards.claimFiring(rule.id)) {
            is FiringClaim.Claimed -> c
            is FiringClaim.InCooldown -> {
                record(rule, trigger, WatchRuleExecutionStatus.SKIPPED, reason = "쿨다운 ${c.cooldownSec}초 이내 재발동")
                return
            }
            is FiringClaim.DailyLimitReached -> {
                record(rule, trigger, WatchRuleExecutionStatus.SKIPPED, reason = "하루 최대 발동 ${c.limit}회 도달")
                return
            }
            FiringClaim.Inactive -> {
                log.info("watch rule 발동 직전 비활성화됨 — 건너뜀 ruleId={} {}", rule.id, trigger)
                return
            }
        }

        var placed = false
        try {
            // ADR-095 — 수량·지정가는 발동권을 잡은 뒤 발동 시점 값으로 정한다. 0주 등으로 건너뛰면 finally가 발동권을 돌려준다.
            val plan = when (val p = planner.plan(rule, trigger.stockId)) {
                is OrderPlan.Ready -> p
                is OrderPlan.Skip -> {
                    record(rule, trigger, WatchRuleExecutionStatus.SKIPPED, reason = p.reason)
                    return
                }
            }
            if (plan.limitPrice == null) {
                val result = orderSubmitter.submitMarket(
                    userId = rule.userId,
                    stockId = trigger.stockId,
                    side = rule.side.name,
                    quantity = plan.quantity,
                    origin = OrderOrigin.watchRule(rule.id),   // ADR-085
                    idempotencyKey = trigger.key(rule.id),
                )
                placed = true
                record(rule, trigger, WatchRuleExecutionStatus.EXECUTED,
                    orderId = result.orderId, fillPrice = result.fillPrice, quantity = result.quantity)
                log.info("watch rule 발동 ruleId={} {} orderId={}", rule.id, trigger, result.orderId)
            } else {
                // ADR-095 — 지정가는 ADR-074 경로 그대로: 교차하면 즉시 체결, 아니면 미체결(예약·스위퍼·신선도).
                val result = orderSubmitter.submitLimit(
                    userId = rule.userId,
                    stockId = trigger.stockId,
                    side = rule.side.name,
                    quantity = plan.quantity,
                    limitPrice = plan.limitPrice,
                    origin = OrderOrigin.watchRule(rule.id),
                    idempotencyKey = trigger.key(rule.id),
                )
                placed = true
                val fill = result.fill
                if (fill != null) {
                    record(rule, trigger, WatchRuleExecutionStatus.EXECUTED,
                        orderId = result.orderId, fillPrice = fill.fillPrice, quantity = fill.quantity, limitPrice = result.limitPrice)
                } else {
                    record(rule, trigger, WatchRuleExecutionStatus.PLACED,
                        orderId = result.orderId, quantity = result.quantity, limitPrice = result.limitPrice,
                        reason = "지정가 ${result.limitPrice.stripTrailingZeros().toPlainString()} 접수 — 미체결")
                }
                log.info("watch rule 지정가 발동 ruleId={} {} orderId={} status={}", rule.id, trigger, result.orderId, result.status)
            }
        } catch (e: RiskLimitException) {
            record(rule, trigger, WatchRuleExecutionStatus.REJECTED, reason = "리스크 한도: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // 잔고 부족·보유 수량 부족 등 사가의 사전 조건 위반.
            record(rule, trigger, WatchRuleExecutionStatus.REJECTED, reason = e.message ?: "주문 거부")
        } catch (e: IllegalStateException) {
            // 현재가 없음·시장가 미체결 등. 사용자 잘못이 아니지만 이 이벤트로는 체결되지 않았다.
            record(rule, trigger, WatchRuleExecutionStatus.REJECTED, reason = e.message ?: "주문 실패")
        } finally {
            // 주문이 나가지 않았으면(건너뜀·거부·인프라 예외 모두) 슬롯과 쿨다운을 돌려준다. 지정가가 접수됐으면(미체결이라도)
            // 발동으로 센다 — 예약금이 잡힌 주문이 있다. 인프라 예외는 삼키지 않는다 — 호출자를 거쳐 컨슈머 재시도로 간다.
            // 재시도해도 멱등 키 덕에 중복 주문은 없다.
            if (!placed) runCatching { guards.releaseFiring(claim) }
                .onFailure { log.warn("watch rule 발동권 반환 실패 ruleId={} — 오늘 한도가 1 적고 쿨다운이 이어진다(안전한 방향)", rule.id) }
        }
    }

    private fun record(
        rule: WatchRule,
        trigger: Trigger,
        status: WatchRuleExecutionStatus,
        orderId: Long? = null,
        fillPrice: java.math.BigDecimal? = null,
        quantity: Int? = null,
        reason: String? = null,
        limitPrice: java.math.BigDecimal? = null,
    ) {
        try {
            execRepo.save(
                WatchRuleExecution(
                    watchRuleId = rule.id,
                    userId = rule.userId,
                    stockEventId = trigger.stockEventId,
                    quantSignalId = trigger.quantSignalId,
                    stockId = trigger.stockId,
                    limitPrice = limitPrice,
                    status = status,
                    orderId = orderId,
                    fillPrice = fillPrice,
                    quantity = quantity,
                    reason = reason,
                )
            )
        } catch (e: DataIntegrityViolationException) {
            // (룰, 이벤트) 유니크 충돌 — 다른 스레드가 같은 이벤트를 먼저 기록했다. 정상 동작이다.
            log.debug("watch rule 기록 중복 무시 ruleId={} {}", rule.id, trigger)
            return
        }
        when (status) {
            WatchRuleExecutionStatus.EXECUTED -> executed
            WatchRuleExecutionStatus.PLACED -> placedCounter
            WatchRuleExecutionStatus.REJECTED -> rejected
            WatchRuleExecutionStatus.SKIPPED -> skipped
        }.increment()
    }

    companion object {
        /** 주문 테이블의 부분 유니크 인덱스(V48)에 들어가는 값. 100자 제한 안에 들어온다. */
        fun idempotencyKey(ruleId: Long, eventId: Long) = "WR:$ruleId:$eventId"

        /** ADR-077 — 퀀트 신호 발동. "WR:" 접두는 같게 두어 거래 내역 경로(TradeRoute)가 그대로 Watch Rule로 읽는다. */
        fun signalIdempotencyKey(ruleId: Long, signalId: Long) = "WR:$ruleId:Q$signalId"

        /** 복합 조건 창의 기본값 — 30분 */
        const val DEFAULT_WINDOW_SEC = 1800
    }
}
