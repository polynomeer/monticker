package com.monticker.api.matching.saga

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * OrderSagaOrchestrator의 현금 예약이 `SELECT cash` → `require(cash >= toReserve)` →
 * `UPDATE cash - toReserve` 3단계였을 때는(과거 구현), 동시에 들어온 두 요청이 같은
 * SELECT 결과를 보고 각자 "잔고 충분" 판정을 내린 뒤 둘 다 UPDATE를 실행할 수 있었다 —
 * mockk 기반 단위 테스트(OrderSagaOrchestratorTest)는 매 호출을 순차 실행하므로 이 레이스를
 * 절대 재현하지 못한다. 실제 Postgres에 실제로 동시 요청을 쏴야만 증명 가능하다.
 *
 * 지금 구현은 `UPDATE paper_accounts SET cash = cash - ? WHERE user_id = ? AND cash >= ?`
 * 하나로 확인과 차감을 원자화했다(OrderSagaOrchestrator.reserveCash) — 이 테스트는 그 SQL을
 * 실제 동시 스레드로 실행해 잔고가 절대 마이너스로 떨어지지 않음을 검증한다.
 */
class CashReservationConcurrencyIntegrationTest : PostgresIntegrationTest() {

    private fun reserveCash(userId: Long, amount: BigDecimal): Boolean {
        val updated = jdbcTemplate.update(
            "UPDATE paper_accounts SET cash = cash - ?, updated_at = now() WHERE user_id = ? AND cash >= ?",
            amount, userId, amount,
        )
        return updated > 0
    }

    private fun createUser(email: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, email, email,
        )!!

    @Test
    fun `concurrent BUY reservations against a shared account never push cash negative`() {
        val userId = createUser("cash-race-${System.nanoTime()}@test.local")
        val initialCash = BigDecimal("1000000")
        jdbcTemplate.update(
            "INSERT INTO paper_accounts (user_id, cash) VALUES (?, ?)",
            userId, initialCash,
        )

        // 계좌 잔고로는 최대 3건만 통과 가능한 금액(400,000)을, 10개 스레드가 동시에 예약 시도.
        // 순차 실행이면 절대 드러나지 않는 레이스이므로 스레드를 latch로 동시 출발시킨다.
        val reserveAmount = BigDecimal("400000")
        val threadCount = 10
        val pool = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        repeat(threadCount) { i ->
            pool.submit {
                try {
                    startLatch.await()
                    results[i] = reserveCash(userId, reserveAmount)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        doneLatch.await(10, TimeUnit.SECONDS)
        pool.shutdown()

        val succeeded = results.values.count { it }
        val finalCash = jdbcTemplate.queryForObject(
            "SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId,
        )!!

        // 1,000,000 / 400,000 → 정확히 2건만 성공해야 한다(3번째부터는 잔고 부족으로 거부).
        assertThat(succeeded).isEqualTo(2)
        assertThat(finalCash).isEqualByComparingTo(initialCash - reserveAmount.multiply(BigDecimal(succeeded)))
        assertThat(finalCash).isGreaterThanOrEqualTo(BigDecimal.ZERO)
    }
}
