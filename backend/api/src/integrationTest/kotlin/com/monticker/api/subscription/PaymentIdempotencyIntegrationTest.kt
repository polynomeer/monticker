package com.monticker.api.subscription

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-053 — "같은 청구주기는 한 번만 청구한다"의 증명.
 *
 * [com.monticker.api.subscription.application.SubscriptionService]의 사전 조회
 * (`findByPgOrderId`)는 빠른 경로일 뿐 방어선이 아니다. 갱신 배치가 두 인스턴스에서
 * 동시에 돌거나(스케줄러 중복 기동), 타임아웃 재시도가 겹치면 두 스레드가 모두 조회를
 * 통과한다 — MockK 단위 테스트는 순차 실행이라 이 레이스를 원리적으로 재현하지 못한다.
 *
 * 실제로 이중청구를 막는 것은 V50의 유니크 인덱스뿐이고, 이 테스트는 그것을 실제 동시
 * 스레드로 친다. 주문 쪽(WatchRuleIdempotencyIntegrationTest)과 같은 방식이다 —
 * 결제만 오래 그 증명이 없었다.
 *
 * 불변조건: **(구독, 청구주기) 하나당 결제 기록은 최대 하나.**
 */
class PaymentIdempotencyIntegrationTest : PostgresIntegrationTest() {

    private fun createUser(tag: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "$tag-${System.nanoTime()}@test.local", tag,
        )!!

    private fun proPlanId(): Long =
        jdbcTemplate.queryForObject(
            "SELECT id FROM subscription_plans WHERE code = 'PRO'", Long::class.java,
        )!!

    private fun insertPayment(userId: Long, orderId: String?, status: String = "PENDING"): Long? =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO payment_records (user_id, plan_id, amount, status, pg_order_id)
            VALUES (?, ?, 9900, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, proPlanId(), status, orderId,
        )

    /** 같은 작업을 N개 스레드가 동시에 시도하고, 성공한 횟수를 돌려준다. */
    private fun raceCount(threads: Int, action: () -> Unit): Int {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val outcomes = ConcurrentHashMap<Int, Boolean>()
        repeat(threads) { i ->
            pool.submit {
                start.await()
                outcomes[i] = runCatching { action() }.isSuccess
                done.countDown()
            }
        }
        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        return outcomes.values.count { it }
    }

    private fun countFor(orderId: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_records WHERE pg_order_id = ?", Int::class.java, orderId,
        )!!

    // ── 동시성 ───────────────────────────────────────────────────────────────

    @Test
    fun `열 개 스레드가 같은 청구주기를 동시에 긁어도 결제 기록은 하나만 생긴다`() {
        val userId = createUser("pay-race")
        val orderId = "renewal_${userId}_1790000000"

        val succeeded = raceCount(10) { insertPayment(userId, orderId) }

        assertThat(succeeded).isEqualTo(1)
        assertThat(countFor(orderId)).isEqualTo(1)
    }

    @Test
    fun `배치를 다시 돌려도 같은 주기는 두 번째 기록을 만들지 못한다`() {
        // 크래시 후 재실행. orderId가 결정적이므로 두 번째 시도는 DB에서 막힌다.
        val userId = createUser("pay-rerun")
        val orderId = "renewal_${userId}_1790000001"
        insertPayment(userId, orderId, status = "SUCCESS")

        val second = runCatching { insertPayment(userId, orderId) }

        assertThat(second.isFailure).isTrue()
        assertThat(countFor(orderId)).isEqualTo(1)
    }

    @Test
    fun `타임스탬프를 섞은 옛 방식이었다면 같은 주기가 두 번 청구된다`() {
        // 회귀 방지용 대조군. 예전 orderId는 `renewal_<id>_<millis>`라 재시도마다 값이
        // 달라져 유니크 인덱스도 토스의 중복 방어도 둘 다 비켜갔다 — 이 테스트는 그
        // 실패 모드가 어떤 모양이었는지를 코드로 남긴다.
        val userId = createUser("pay-legacy")
        val legacyStyle = { "renewal_${userId}_${System.nanoTime()}" }

        insertPayment(userId, legacyStyle())
        insertPayment(userId, legacyStyle())

        val total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_records WHERE user_id = ?", Int::class.java, userId,
        )!!
        assertThat(total).isEqualTo(2)   // 막지 못한다 — 그래서 결정적 orderId가 필요했다
    }

    // ── 다른 주기·다른 구독은 서로 막지 않는다 ────────────────────────────────

    @Test
    fun `다음 청구주기는 막히지 않는다`() {
        val userId = createUser("pay-next")
        insertPayment(userId, "renewal_${userId}_1790000000", status = "SUCCESS")

        val next = runCatching { insertPayment(userId, "renewal_${userId}_1792592000") }

        assertThat(next.isSuccess).isTrue()
    }

    @Test
    fun `다른 사용자의 갱신은 서로 간섭하지 않는다`() {
        val a = createUser("pay-a")
        val b = createUser("pay-b")

        insertPayment(a, "renewal_${a}_1790000000")
        val other = runCatching { insertPayment(b, "renewal_${b}_1790000000") }

        assertThat(other.isSuccess).isTrue()
    }

    // ── 일회성 결제(orderId 없음)는 제약 밖이다 ───────────────────────────────

    @Test
    fun `orderId가 없는 일회성 결제는 유니크 제약에 걸리지 않는다`() {
        // 부분 유니크 인덱스(WHERE pg_order_id IS NOT NULL)라 NULL은 여러 건 허용된다.
        // 이게 깨지면 confirm 플로우 결제가 사용자당 한 건만 저장된다.
        val userId = createUser("pay-null")

        repeat(3) { insertPayment(userId, null) }

        val nulls = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_records WHERE user_id = ? AND pg_order_id IS NULL",
            Int::class.java, userId,
        )!!
        assertThat(nulls).isEqualTo(3)
    }
}
