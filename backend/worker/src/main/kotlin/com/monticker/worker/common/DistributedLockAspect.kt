package com.monticker.worker.common

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Aspect
@Component
class DistributedLockAspect(private val redis: StringRedisTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Around("@annotation(com.monticker.worker.common.DistributedLock)")
    fun around(pjp: ProceedingJoinPoint): Any? {
        val method = (pjp.signature as MethodSignature).method
        val lock = method.getAnnotation(DistributedLock::class.java)
        val key = "distributed-lock:${lock.name}"

        val acquired = redis.opsForValue()
            .setIfAbsent(key, "1", Duration.ofSeconds(lock.ttlSeconds))

        if (acquired != true) {
            log.debug("[DistributedLock] 다른 인스턴스가 실행 중 — 건너뜀: {}", lock.name)
            return null
        }

        return try {
            pjp.proceed()
        } finally {
            redis.delete(key)
        }
    }
}
