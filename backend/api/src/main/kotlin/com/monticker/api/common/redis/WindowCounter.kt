package com.monticker.api.common.redis

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration

/**
 * 고정 창 카운터 — 레이트리밋용. INCR과 EXPIRE를 Lua 스크립트 한 번으로 묶는다.
 *
 * 이전 구현은 `INCR` 뒤 결과가 1일 때만 따로 `EXPIRE`를 걸었다. 두 명령 사이에서 EXPIRE가 빠지면(Redis 순간 장애·타임아웃으로
 * fail-open 가드가 삼킴, 프로세스 종료) 키가 만료 없이 남아 그 IP·사용자는 **영구히** 429를 받았다 — 로컬에서 실제로 TTL -1인
 * `rate:api:<ip>` 키가 발견됐다. 공유 프록시·NAT 뒤라면 사용자 전체가 막힌다.
 *
 * 스크립트는 만료가 없는(TTL < 0) 키에도 만료를 건다 — 이미 갇혀 있던 키도 다음 요청 때 스스로 풀린다.
 */
object WindowCounter {
    private val SCRIPT = DefaultRedisScript(
        """
        local c = redis.call('INCR', KEYS[1])
        if redis.call('TTL', KEYS[1]) < 0 then
          redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
        end
        return c
        """.trimIndent(),
        Long::class.java,
    )

    /** [key]를 1 올리고 이 창에서의 누적 횟수를 돌려준다. 창은 첫 증가 시점부터 [window]. */
    fun incrementInWindow(redis: StringRedisTemplate, key: String, window: Duration): Long =
        redis.execute(SCRIPT, listOf(key), window.seconds.coerceAtLeast(1).toString()) ?: 1L
}
