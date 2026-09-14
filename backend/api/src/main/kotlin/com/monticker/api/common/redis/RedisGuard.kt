package com.monticker.api.common.redis

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/**
 * Redis 장애 시 동작 정책을 한 곳에 모은다 (resilience-plan §A1, P0-1).
 *
 * 이 가드가 생기기 전에는 RateLimitFilter·IdempotencyFilter·RateLimitedAspect가 Redis 예외를
 * 잡지 않아, Redis가 죽으면 /api/ 하위 전체가 500이 됐다 — Redis가 캐시가 아니라 하드 의존성이었다.
 *
 * 정책은 두 가지뿐이다:
 *  - failOpen  : Redis 없이도 진행한다. 남용 방어(레이트리밋, 로그인 실패 카운터)처럼
 *                "없어도 서비스는 성립하는" 보조 기능에 쓴다.
 *  - failClosed: null을 돌려주고 호출자가 명시적 503으로 거절하게 한다. 멱등성처럼
 *                "없으면 돈이 두 번 나갈 수 있는" 안전장치에 쓴다.
 *
 * 어느 쪽이든 실패는 redis_command_failed_total{op,policy} 카운터에 남긴다 —
 * fail-open이 조용히 발동해 레이트리밋이 몇 주째 꺼져 있는 상황을 감지하기 위해서다.
 */
@Component
class RedisGuard(private val registry: MeterRegistry) {
    init {
        // 카운터는 첫 증가 때 생긴다 — 그 전엔 시계열이 없어 increase() 기반 알람·대시보드가 "데이터 없음"이다.
        // 알려진 (op, policy) 조합을 0으로 미리 등록해 IdempotencyStoreDown·RedisFailOpenSustained가 처음부터 검증 가능하게 한다.
        listOf("idempotency_get" to "closed", "idempotency_set" to "closed",
               "rate_limit" to "open", "rate_limited_aspect" to "open",
               "login_fail_get" to "open", "login_fail_incr" to "open", "login_fail_reset" to "open",
               "signup_verify_token" to "open", "market_summary_get" to "open", "alert_rules_changed_publish" to "open")
            .forEach { (op, policy) -> registry.counter("redis_command_failed_total", "op", op, "policy", policy) }
    }


    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> failOpen(op: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: DataAccessException) {
            record(op, "open", e)
            fallback
        }

    fun <T> failClosed(op: String, block: () -> T): T? =
        try {
            block()
        } catch (e: DataAccessException) {
            record(op, "closed", e)
            null
        }

    private fun record(op: String, policy: String, e: Exception) {
        registry.counter("redis_command_failed_total", "op", op, "policy", policy).increment()
        // 예외 스택은 매 요청마다 찍으면 로그가 폭주한다 — 메시지만 남기고 카운터로 추이를 본다.
        log.warn("[RedisGuard] op={} policy=fail-{} — {}", op, policy, e.message)
    }
}
