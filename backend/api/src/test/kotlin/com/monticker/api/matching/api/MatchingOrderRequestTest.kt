package com.monticker.api.matching.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.monticker.api.matching.submit.OrderOrigin
import com.monticker.api.matching.submit.OrderOriginType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** ADR-085 — 사용자 요청 본문으로 서버 내부 필드(멱등 키·진입 출처)를 정할 수 없다. */
class MatchingOrderRequestTest {

    // Spring Boot 기본값과 같다: 알 수 없는 필드는 무시한다
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `client supplied idempotency key and origin are ignored — the order is always MANUAL`() {
        val body = """{"stockId":1,"side":"BUY","orderType":"MARKET","quantity":3,
            "idempotencyKey":"WR:5:123","origin":{"type":"WATCH_RULE","ref":5}}"""

        val req = mapper.readValue<MatchingOrderRequest>(body).toSubmitRequest()

        assertThat(req.idempotencyKey).isNull()
        assertThat(req.origin).isEqualTo(OrderOrigin.MANUAL)
    }

    @Test
    fun `origin requires a ref for non-manual types and forbids one for MANUAL`() {
        assertThatThrownBy { OrderOrigin(OrderOriginType.WATCH_RULE) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OrderOrigin(OrderOriginType.MANUAL, 1L) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(OrderOrigin.conditional(9L).ref).isEqualTo(9L)
    }
}
