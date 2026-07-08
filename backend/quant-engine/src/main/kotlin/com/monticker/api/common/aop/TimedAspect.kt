package com.monticker.api.common.aop

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component

@Aspect
@Component
class TimedAspect(private val meterRegistry: MeterRegistry) {

    @Around("@annotation(timed)")
    fun measure(pjp: ProceedingJoinPoint, timed: Timed): Any? {
        val metricName = timed.value.ifBlank {
            val sig = pjp.signature as MethodSignature
            "${sig.declaringType.simpleName}.${sig.name}".lowercase().replace('.', '_')
        }

        val tagPairs = timed.tags
            .map { it.split("=", limit = 2) }
            .filter { it.size == 2 }
            .flatMap { (k, v) -> listOf(k, v) }
            .toTypedArray()

        val sample = Timer.start(meterRegistry)
        return try {
            pjp.proceed()
        } finally {
            sample.stop(
                Timer.builder(metricName)
                    .tags(*tagPairs)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            )
        }
    }
}
