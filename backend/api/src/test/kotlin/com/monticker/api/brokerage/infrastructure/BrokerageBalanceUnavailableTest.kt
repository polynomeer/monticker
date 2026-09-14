package com.monticker.api.brokerage.infrastructure

import com.monticker.api.common.exception.ExternalServiceUnavailableException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * CH-06 부수 관찰(resilience-plan §6.3): 서킷브레이커가 열리면 잔고 조회가 `BrokerageBalance(0, 0, [])`를 돌려줬다.
 * 증권사 장애 중 사용자에게 "잔고 0원·보유 없음"이 보이고, 리스크 게이트와 리밸런싱은 빈 포트폴리오를 사실로 믿는다.
 * 조회 불가는 503으로 — 주문 경로(submitOrder)와 같은 규칙이다.
 */
class BrokerageBalanceUnavailableTest {

    private fun openRegistry(name: String) = CircuitBreakerRegistry.ofDefaults().also { it.circuitBreaker(name).transitionToOpenState() }
    private val creds = BrokerageCredentials(token = BrokerageToken("t", 3600), appKey = "k", appSecret = "s", accountNumber = "12345678-01")

    @Test
    fun `KIS balance with an open circuit is a 503, not zero won`() {
        val client = KisBrokerageClient("https://openapivts.koreainvestment.com:29443", openRegistry("kis"))

        assertThatThrownBy { client.getBalance(creds) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
            .hasMessageContaining("잔고를 확인할 수 없습니다")
    }

    @Test
    fun `Toss balance with an open circuit is a 503, not zero won`() {
        val client = TossBrokerageClient("https://api.tossinvest.com", openRegistry("toss"))

        assertThatThrownBy { client.getBalance(creds) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    // 브로커가 응답을 못 하는 경우(연결 거부)도 0원이 아니라 503이다
    @Test
    fun `KIS balance with an unreachable broker is a 503, not zero won`() {
        val client = KisBrokerageClient("http://127.0.0.1:1", CircuitBreakerRegistry.ofDefaults())

        assertThatThrownBy { client.getBalance(creds) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)
    }
}
