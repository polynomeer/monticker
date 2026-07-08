package com.monticker.api.common.aop

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * @Audited 메서드의 호출자 IP, 액션명, 실행 시간, 성공/실패를 구조화 로그로 기록한다.
 * BatchJobController 등 관리자 API에 클래스 레벨로 적용할 수 있다.
 */
@Aspect
@Component
class AuditAspect {

    private val log = LoggerFactory.getLogger("AUDIT")

    @Around("@within(audited) || @annotation(audited)")
    fun audit(pjp: ProceedingJoinPoint, audited: Audited?): Any? {
        val sig    = pjp.signature as MethodSignature
        val action = audited?.action?.ifBlank { null }
            ?: "${sig.declaringType.simpleName}.${sig.name}"

        val ip = runCatching {
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
                ?.request?.remoteAddr ?: "unknown"
        }.getOrDefault("unknown")

        val start = System.currentTimeMillis()
        return try {
            val result = pjp.proceed()
            val elapsed = System.currentTimeMillis() - start
            log.info("[AUDIT] action={} ip={} elapsed={}ms status=OK", action, ip, elapsed)
            result
        } catch (ex: Exception) {
            val elapsed = System.currentTimeMillis() - start
            log.warn("[AUDIT] action={} ip={} elapsed={}ms status=FAIL error={}", action, ip, elapsed, ex.message)
            throw ex
        }
    }
}
