package com.monticker.api.common.tracing

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context

object Tracing {

    private val tracer by lazy {
        GlobalOpenTelemetry.getTracer("monticker", "1.0.0")
    }

    fun <T> span(name: String, attributes: Map<String, Any> = emptyMap(), block: (Span) -> T): T {
        val span = tracer.spanBuilder(name)
            .setParent(Context.current())
            .startSpan()
        return try {
            attributes.forEach { (k, v) ->
                when (v) {
                    is String  -> span.setAttribute(k, v)
                    is Long    -> span.setAttribute(k, v)
                    is Int     -> span.setAttribute(k, v.toLong())
                    is Double  -> span.setAttribute(k, v)
                    is Boolean -> span.setAttribute(k, v)
                    else       -> span.setAttribute(k, v.toString())
                }
            }
            span.makeCurrent().use { block(span) }
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "error")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }
}
