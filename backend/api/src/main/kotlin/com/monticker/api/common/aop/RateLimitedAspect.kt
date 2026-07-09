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
 * Redis 키: ratelimit:{keyPrefix}:{subject}
 * subject 추출 우선순위:
 *   1. 파라미터명이 "userId"인 Long 파라미터
 *   2. SecurityContextHolder — 인증된 요청에서 Long principal 추출
 *   3. "anon" — 미인증 또는 추출 실패 시 (인증 엔드포인트는 RateLimitFilter IP 기반으로 처리)
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

        val subject  = extractSubject(sig.parameterNames, pjp.args)
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

    private fun extractSubject(names: Array<String>, args: Array<Any?>): String {
        // 1. 명시적 userId 파라미터
        val idx = names.indexOf("userId")
        if (idx >= 0) {
            (args[idx] as? Long)?.let { return it.toString() }
        }
        // 2. SecurityContextHolder — 컨트롤러 메서드가 내부적으로 userId()를 호출하는 경우
        runCatching {
            val principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().authentication?.principal
            if (principal is Long) return principal.toString()
        }
        return "anon"
    }
}
