package com.monticker.api.common.aop

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/**
 * @RateLimited: userId 기반 메서드 레벨 Rate Limiting.
 * 기존 RateLimitFilter(IP 기반, 전역)를 보완한다.
 *
 * Redis 키: ratelimit:{keyPrefix}:{userId}
 * userId는 Long 타입 첫 번째 파라미터에서 추출한다.
 * userId 파라미터가 없으면 IP 기반으로 폴백한다.
 */
@Aspect
@Component
class RateLimitedAspect(private val redis: StringRedisTemplate) {

    @Around("@annotation(rateLimited)")
    fun limit(pjp: ProceedingJoinPoint, rateLimited: RateLimited): Any? {
        val sig       = pjp.signature as MethodSignature
        val keyPrefix = rateLimited.keyPrefix.ifBlank {
            "${sig.declaringType.simpleName}.${sig.name}"
        }

        val subject = extractUserId(sig.parameterNames, pjp.args)?.toString() ?: "anon"
        val redisKey = "ratelimit:$keyPrefix:$subject"

        val count = redis.opsForValue().increment(redisKey) ?: 1L
        if (count == 1L) {
            redis.expire(redisKey, Duration.ofSeconds(rateLimited.windowSec))
        }

        if (count > rateLimited.limit) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "요청 한도 초과: $keyPrefix (${rateLimited.limit}회/${rateLimited.windowSec}초)"
            )
        }

        return pjp.proceed()
    }

    private fun extractUserId(names: Array<String>, args: Array<Any?>): Long? {
        val idx = names.indexOf("userId")
        return if (idx >= 0) args[idx] as? Long else args.firstOrNull { it is Long } as? Long
    }
}
