package com.monticker.api.matching.saga

import com.monticker.api.support.PostgresIntegrationTest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-052 — 현금 예약 락 전략 비교. 같은 부하를 세 구현에 똑같이 걸어 **실측**한다.
 *
 * 비교 대상:
 *  - ATOMIC     : `UPDATE … SET cash = cash - ? WHERE user_id = ? AND cash >= ?` (현재 구현)
 *  - PESSIMISTIC: `SELECT … FOR UPDATE` → 애플리케이션에서 검사 → `UPDATE` (한 트랜잭션)
 *  - OPTIMISTIC : `SELECT cash, version` → 검사 → `UPDATE … WHERE version = ?` → 실패 시 재시도
 *
 * 이 테스트는 **정확성만 단언**하고 수치는 출력한다. 로컬 Testcontainers 한 대에서 잰 값을 SLO나
 * 운영 용량으로 읽으면 안 되기 때문이다 — 측정 조건과 한계는 ADR-052에 함께 적었다.
 * 정확성 단언(잔고 음수 없음, 성공 횟수 = 이론값)은 세 전략 모두에 대해 항상 검사한다.
 *
 * 커넥션은 **HikariCP로 푼다**. 베이스 클래스의 DriverManagerDataSource 를 그대로 쓴 첫 측정은
 * 매 연산마다 TCP 연결을 새로 맺어 p50이 50ms대로 나왔고, 그 비용이 전략 간 차이를 덮었다
 * (ATOMIC 319 ops/s vs PESSIMISTIC 322 ops/s — 구분이 안 됐다). 운영은 Hikari를 쓰므로
 * 그쪽에 맞춘다. 경위는 ADR-052 "측정 방법" 에 남겼다.
 */
class CashReservationStrategyBenchmark : PostgresIntegrationTest() {

    private val threads = 20
    private val attemptsPerThread = 25
    private val rounds = 3

    /** 운영과 같은 풀링 동작으로 재려고 Hikari를 쓴다 — 풀 크기는 api 기본값(10)에 맞춘다. */
    private val pooled: HikariDataSource by lazy {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = POOL_SIZE
            poolName = "bench-pool"
        })
    }

    private val txTemplate: TransactionTemplate by lazy {
        TransactionTemplate(DataSourceTransactionManager(pooled))
    }

    private fun createAccount(tag: String, cash: BigDecimal): Long {
        val userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "$tag-${System.nanoTime()}@bench.local", tag,
        )!!
        jdbcTemplate.update("INSERT INTO paper_accounts (user_id, cash) VALUES (?, ?)", userId, cash)
        return userId
    }

    // ── 전략 3종 ────────────────────────────────────────────────────────────────

    /** 확인과 차감이 한 문장 — 락을 애플리케이션이 잡지 않고 행 단위 쓰기 락만 쓴다. */
    private fun atomic(jdbc: JdbcTemplate, userId: Long, amount: BigDecimal): Boolean =
        jdbc.update(
            "UPDATE paper_accounts SET cash = cash - ?, updated_at = now() WHERE user_id = ? AND cash >= ?",
            amount, userId, amount,
        ) > 0

    /** 행을 먼저 잠그고 읽은 뒤 판단한다 — 트랜잭션 전체가 직렬화된다. */
    private fun pessimistic(jdbc: JdbcTemplate, userId: Long, amount: BigDecimal): Boolean =
        txTemplate.execute {
            val cash = jdbc.queryForObject(
                "SELECT cash FROM paper_accounts WHERE user_id = ? FOR UPDATE", BigDecimal::class.java, userId,
            )!!
            if (cash < amount) return@execute false
            jdbc.update("UPDATE paper_accounts SET cash = cash - ?, updated_at = now() WHERE user_id = ?", amount, userId)
            true
        }!!

    /**
     * 읽고 판단한 뒤 "그 사이 아무도 안 바꿨을 때만" 쓴다. 여기서는 version 컬럼 대신 읽은 cash 값을
     * 조건으로 써서 같은 효과를 낸다(CAS) — 스키마 변경 없이 비교하기 위한 선택이고, 경합 시 재시도가
     * 필요하다는 성질은 같다. 재시도 횟수를 세어 ADR에 남긴다.
     */
    private fun optimistic(jdbc: JdbcTemplate, userId: Long, amount: BigDecimal, retries: AtomicInteger): Boolean {
        repeat(MAX_CAS_RETRIES) {
            val cash = jdbc.queryForObject(
                "SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId,
            )!!
            if (cash < amount) return false
            val updated = jdbc.update(
                "UPDATE paper_accounts SET cash = cash - ?, updated_at = now() WHERE user_id = ? AND cash = ?",
                amount, userId, cash,
            )
            if (updated > 0) return true
            retries.incrementAndGet()
        }
        return false   // 재시도 소진 — 경합에 밀렸다
    }

    // ── 측정 ────────────────────────────────────────────────────────────────────

    private data class Measurement(
        val strategy: String,
        val succeeded: Int,
        val elapsedMs: Long,
        val p50Micros: Long,
        val p95Micros: Long,
        val p99Micros: Long,
        val casRetries: Int,
    ) {
        val opsPerSec: Long get() = if (elapsedMs == 0L) 0 else TOTAL_OPS * 1000L / elapsedMs
    }

    private fun run(strategy: String, userId: Long, amount: BigDecimal, op: (JdbcTemplate, Long) -> Boolean): Measurement {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val succeeded = AtomicInteger()
        val latencies = ConcurrentLinkedQueue<Long>()

        repeat(threads) {
            pool.submit {
                val jdbc = JdbcTemplate(pooled)
                start.await()
                repeat(attemptsPerThread) {
                    val t0 = System.nanoTime()
                    val ok = runCatching { op(jdbc, userId) }.getOrDefault(false)
                    latencies.add((System.nanoTime() - t0) / 1_000)
                    if (ok) succeeded.incrementAndGet()
                }
                done.countDown()
            }
        }
        val t0 = System.currentTimeMillis()
        start.countDown()
        assertThat(done.await(120, TimeUnit.SECONDS)).`as`("$strategy 가 120초 안에 끝나야 한다").isTrue()
        val elapsed = System.currentTimeMillis() - t0
        pool.shutdown()

        val sorted = latencies.sorted()
        fun pct(p: Double) = sorted[(sorted.size * p).toInt().coerceAtMost(sorted.size - 1)]
        return Measurement(strategy, succeeded.get(), elapsed, pct(0.50), pct(0.95), pct(0.99), 0)
    }

    @Test
    fun `all three reservation strategies stay correct and their cost is measured`() {
        // 계좌를 정확히 200번만 통과할 수 있게 잡는다 — 500번 시도하므로 300번은 잔고 부족으로 실패해야 한다.
        // 이 "성공 횟수 = 이론값" 단언이 정확성 검사다: 이중 차감이 있으면 잔고가 음수가 되고,
        // 놓친 차감이 있으면 성공 횟수가 200을 넘는다.
        val unit = BigDecimal("1000")
        val allowed = 200
        val initial = unit.multiply(BigDecimal(allowed))
        val results = mutableListOf<Measurement>()

        // 워밍업 — 첫 라운드는 커넥션 생성·플랜 캐시 때문에 느리다. 측정에서 제외한다.
        createAccount("warmup", initial).let { warm -> run("warmup", warm, unit) { j, u -> atomic(j, u, unit) } }

        repeat(rounds) { round ->
            val atomicUser = createAccount("atomic-r$round", initial)
            results += run("ATOMIC", atomicUser, unit) { j, u -> atomic(j, u, unit) }
                .also { assertCorrect("ATOMIC", atomicUser, it, allowed, initial) }

            val pessimisticUser = createAccount("pessimistic-r$round", initial)
            results += run("PESSIMISTIC", pessimisticUser, unit) { j, u -> pessimistic(j, u, unit) }
                .also { assertCorrect("PESSIMISTIC", pessimisticUser, it, allowed, initial) }

            val retries = AtomicInteger()
            val optimisticUser = createAccount("optimistic-r$round", initial)
            results += run("OPTIMISTIC", optimisticUser, unit) { j, u -> optimistic(j, u, unit, retries) }
                .copy(casRetries = retries.get())
                .also { assertCorrect("OPTIMISTIC", optimisticUser, it, allowed, initial, exact = false) }
        }

        report(results)
    }

    /**
     * @param exact 낙관적 전략은 재시도를 소진하면 통과 가능한 시도를 놓칠 수 있다 — 그래서 상한만 본다.
     *   **잔고 음수와 이중 차감은 세 전략 모두 허용하지 않는다.**
     */
    private fun assertCorrect(
        name: String, userId: Long, m: Measurement, allowed: Int, initial: BigDecimal, exact: Boolean = true,
    ) {
        val cash = jdbcTemplate.queryForObject(
            "SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId,
        )!!
        assertThat(cash).`as`("$name: 잔고가 음수가 되면 안 된다").isGreaterThanOrEqualTo(BigDecimal.ZERO)
        assertThat(m.succeeded).`as`("$name: 허용치보다 많이 통과하면 이중 차감이다").isLessThanOrEqualTo(allowed)
        if (exact) {
            assertThat(m.succeeded).`as`("$name: 통과 가능한 시도를 놓치면 안 된다").isEqualTo(allowed)
        }
        // 불변조건 — 빠져나간 현금은 성공 횟수와 정확히 일치한다.
        val expected = initial.subtract(BigDecimal("1000").multiply(BigDecimal(m.succeeded)))
        assertThat(cash).`as`("$name: 잔고 = 초기 - (성공 × 단가)").isEqualByComparingTo(expected)
    }

    private fun report(results: List<Measurement>) {
        val byStrategy = results.groupBy { it.strategy }
        println()
        println("=== ADR-052 현금 예약 락 전략 벤치마크 ===")
        println("조건: threads=$threads, attempts/thread=$attemptsPerThread, total ops=$TOTAL_OPS, rounds=$rounds")
        println("환경: Testcontainers timescale/timescaledb:latest-pg16, 로컬 Docker, HikariCP pool=$POOL_SIZE, 워밍업 1라운드 제외")
        println("%-12s %8s %10s %10s %10s %10s %10s".format("strategy", "ops/s", "elapsed", "p50(us)", "p95(us)", "p99(us)", "retries"))
        byStrategy.forEach { (name, runs) ->
            println(
                "%-12s %8d %9dms %10d %10d %10d %10d".format(
                    name,
                    runs.map { it.opsPerSec }.average().toLong(),
                    runs.map { it.elapsedMs }.average().toLong(),
                    runs.map { it.p50Micros }.average().toLong(),
                    runs.map { it.p95Micros }.average().toLong(),
                    runs.map { it.p99Micros }.average().toLong(),
                    runs.sumOf { it.casRetries },
                )
            )
        }
        println()
    }

    companion object {
        private const val MAX_CAS_RETRIES = 50
        private const val POOL_SIZE = 10
        private const val TOTAL_OPS = 20 * 25L
    }
}
