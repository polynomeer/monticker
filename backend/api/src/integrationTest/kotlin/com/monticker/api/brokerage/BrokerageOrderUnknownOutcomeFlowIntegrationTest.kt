package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.application.PendingBuyQuery
import com.monticker.api.brokerage.application.TradingHaltService
import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRepository
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.brokerage.infrastructure.MockBrokerageClient
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckAuditLogger
import com.monticker.api.risk.application.RiskCheckerService
import com.monticker.api.risk.application.RiskRuleQueryService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.aop.framework.ProxyFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-056 — 결과 불명 흐름을 **실제 Postgres + 실제 JPA 트랜잭션**으로 끝까지 돌린다.
 *
 * 단위 테스트는 트랜잭션 매니저를 목으로 대신해서, 3단 트랜잭션(REQUIRES_NEW)이 실제로 독립 커밋되는지,
 * tx2가 tx1이 커밋한 행을 다시 읽을 수 있는지, advisory lock·중복 가드·SKIP LOCKED 대조가 실제 SQL로
 * 맞물리는지는 보여주지 못한다. 증권사는 Mock 클라이언트의 "응답 유실" 모드로 흉내 낸다 — 주문은 Mock
 * 증권사에 접수되지만 우리에게는 INDETERMINATE가 돌아온다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스가 여는 트랜잭션이 실제로 커밋되게
class BrokerageOrderUnknownOutcomeFlowIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("monticker")
                .withUsername("monticker")
                .withPassword("monticker")

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired private lateinit var accountRepo: BrokerageAccountRepository
    @Autowired private lateinit var orderRepo: BrokerageOrderRepository
    @Autowired private lateinit var settlementRepo: BrokerageSettlementRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private val lostSymbol = "LOST01"     // Mock 증권사가 접수하고 응답을 잃어버리는 종목
    private val normalSymbol = "OK0001"

    @Autowired private lateinit var riskLimitRepo: com.monticker.api.risk.infrastructure.RiskLimitRepository

    /** ADR-058 — 리스크 게이트까지 실제로 조립한다(룰 판정·감사 기록이 실제 SQL로 돈다). */
    private fun serviceWithRealRiskGate(): BrokerageService = service(
        RiskCheckerService(
            riskLimitRepo, RiskRuleQueryService(jdbc),
            // 운영처럼 트랜잭션 프록시를 씌운다 — record()는 REQUIRES_NEW라 거부와 함께 롤백되지 않고 남아야 한다.
            ProxyFactory(RiskCheckAuditLogger(jdbc, com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())).apply {
                isProxyTargetClass = true
                addAdvice(TransactionInterceptor(txManager, AnnotationTransactionAttributeSource()))
            }.proxy as RiskCheckAuditLogger,
            SimpleMeterRegistry(), jdbc,
        ),
    )

    private fun service(
        riskChecker: RiskCheckerService = mockk {
            every { checkBrokerageOrder(any(), any(), any(), any(), any(), any()) } returns
                RiskCheckResult(approved = true, blockedBy = null, severity = "APPROVED", checks = emptyList())
        },
    ): BrokerageService {
        val mockBroker = MockBrokerageClient(jdbc, indeterminateSymbolsRaw = lostSymbol)
        val target = BrokerageService(
            BrokerageClientRegistry(BrokerageProvider.entries.associateWith { mockBroker }),
            accountRepo, orderRepo, settlementRepo, mockk(relaxed = true), riskChecker, jdbc,
            txManager, SimpleMeterRegistry(), TradingHaltService(jdbc, SimpleMeterRegistry()), PendingBuyQuery(jdbc),
        )
        // 운영처럼 @Transactional 프록시를 씌운다. 직접 생성한 인스턴스는 애노테이션이 무시돼, 예컨대 syncOrderStatus에
        // 바깥 트랜잭션이 생겨 캐시된(해소 전) 엔티티를 돌려주는 회귀를 이 테스트가 잡지 못했다.
        return ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(txManager, AnnotationTransactionAttributeSource()))
        }.proxy as BrokerageService
    }

    private fun seedUser(): Long {
        val userId = jdbc.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, 'flow') RETURNING id", Long::class.java,
            "flow-${System.nanoTime()}@test.local",
        )!!
        accountRepo.save(
            BrokerageAccount(
                userId = userId, provider = BrokerageProvider.MOCK, accountNumber = "F${System.nanoTime() % 100000000}",
                accessToken = "t", tokenExpiresAt = Instant.now().plusSeconds(86_400), appKey = "k", appSecret = "s",
            )
        )
        for (symbol in listOf(lostSymbol, normalSymbol)) {
            jdbc.update(
                "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') ON CONFLICT DO NOTHING",
                symbol, symbol,
            )
            jdbc.update(
                """
                INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time)
                SELECT id, 70000, 70000, 70000, 70000, 1, date_trunc('minute', now()) FROM stocks WHERE symbol = ?
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                symbol,
            )
        }
        return userId
    }

    private fun ageOrder(id: Long, seconds: Long) =
        jdbc.update("UPDATE brokerage_orders SET submitted_at = submitted_at - make_interval(secs => ?) WHERE id = ?", seconds.toDouble(), id)

    private fun row(id: Long): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM brokerage_orders WHERE id = ?", id)

    @Test
    fun `응답 유실 → UNKNOWN 커밋 → 같은 종목·방향 재주문 차단 → 대조가 Mock 증권사 주문에 연결해 FILLED로 해소`() {
        val svc = service()
        val userId = seedUser()

        val unknown = svc.submitOrder(userId, BrokerageOrderRequest(lostSymbol, "BUY", "MARKET", 3))

        // 거부가 아니라 불명. 커밋돼 있어 다른 트랜잭션에서도 보인다.
        assertThat(unknown.status).isEqualTo(BrokerageOrderStatus.UNKNOWN)
        assertThat(row(unknown.id)["status"]).isEqualTo("UNKNOWN")
        assertThat(row(unknown.id)["pg_order_id"]).isNull()
        assertThat(row(unknown.id)["client_order_id"] as String).startsWith("mt-")

        // "실패한 줄 알고 다시 누르기" — 증권사에 가기 전에 막힌다.
        assertThatThrownBy { svc.submitOrder(userId, BrokerageOrderRequest(lostSymbol, "BUY", "MARKET", 3)) }
            .isInstanceOf(BusinessRuleException::class.java)
            .hasMessageContaining("확인 중")
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brokerage_orders WHERE user_id = ?", Long::class.java, userId)).isEqualTo(1L)

        // 사용자의 "다시 확인" — 대조를 즉시 돌리고 **해소된 상태를** 돌려줘야 한다(바깥 트랜잭션이 있으면 캐시된
        // UNKNOWN을 돌려줬다). Mock 증권사의 당일 주문 목록에서 정확히 1건을 찾아 연결한다.
        val synced = svc.syncOrderStatus(userId, unknown.id)

        assertThat(synced.status).isEqualTo(BrokerageOrderStatus.FILLED)
        val resolved = row(unknown.id)
        assertThat(resolved["status"]).isEqualTo("FILLED")
        assertThat(resolved["pg_order_id"] as String).startsWith("KIS")
        assertThat(resolved["resolved_by"]).isEqualTo("BROKER_LOOKUP")
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brokerage_settlements WHERE order_id = ?", Long::class.java, unknown.id))
            .isEqualTo(1L)

        // 해소 뒤에는 다시 주문할 수 있다.
        assertThat(svc.submitOrder(userId, BrokerageOrderRequest(lostSymbol, "SELL", "MARKET", 3)).status)
            .isEqualTo(BrokerageOrderStatus.UNKNOWN)   // 이 종목은 계속 응답을 잃는다 — 반대 방향은 막히지 않는다
    }

    @Test
    fun `정상 응답은 tx1(PENDING_SUBMIT) → tx2를 거쳐 바로 FILLED`() {
        val svc = service()
        val userId = seedUser()

        val order = svc.submitOrder(userId, BrokerageOrderRequest(normalSymbol, "BUY", "MARKET", 2))

        assertThat(order.status).isEqualTo(BrokerageOrderStatus.FILLED)
        assertThat(row(order.id)["status"]).isEqualTo("FILLED")
        assertThat(row(order.id)["reconcile_attempts"]).isEqualTo(0)
    }

    @Test
    fun `제출 중 크래시(PENDING_SUBMIT만 남음)이고 증권사에도 없으면 — 유예 뒤 미접수로 확정`() {
        val svc = service()
        val userId = seedUser()
        val accountId = jdbc.queryForObject("SELECT id FROM brokerage_accounts WHERE user_id = ?", Long::class.java, userId)!!
        val orphan = jdbc.queryForObject(
            """
            INSERT INTO brokerage_orders (user_id, account_id, symbol, side, order_type, quantity, status, client_order_id)
            VALUES (?, ?, ?, 'SELL', 'MARKET', 5, 'PENDING_SUBMIT', ?) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, accountId, normalSymbol, "mt-orphan-${System.nanoTime()}",
        )!!

        // 막 기록된 PENDING_SUBMIT은 아직 호출 중일 수 있다 — 건드리지 않는다.
        assertThat(svc.reconcileUnresolved(orphan)).isEqualTo(BrokerageService.ReconcileResult.SKIPPED)

        ageOrder(orphan, 60)
        assertThat(svc.reconcileUnresolved(orphan)).isEqualTo(BrokerageService.ReconcileResult.WAITING)   // 목록 반영 지연 유예

        ageOrder(orphan, 120)
        assertThat(svc.reconcileUnresolved(orphan)).isEqualTo(BrokerageService.ReconcileResult.NOT_FOUND)
        assertThat(row(orphan)["status"]).isEqualTo("REJECTED")
        assertThat(row(orphan)["resolved_by"]).isEqualTo("NOT_FOUND")
        assertThat(row(orphan)["reconcile_attempts"]).isEqualTo(2)
    }

    @Test
    fun `킬 스위치 — 켜면 다음 주문부터 막히고(행도 안 생김), 취소는 계속 되며, 해제하면 다시 주문된다`() {
        val svc = service()
        val userId = seedUser()
        val halts = TradingHaltService(jdbc, SimpleMeterRegistry())
        // 스위치 전에 낸 미체결 지정가(현재가 70000보다 한참 낮아 체결되지 않는다)
        val resting = svc.submitOrder(userId, BrokerageOrderRequest(normalSymbol, "BUY", "LIMIT", 1, BigDecimal("1")))
        assertThat(resting.status).isEqualTo(BrokerageOrderStatus.SUBMITTED)

        val halt = halts.halt(com.monticker.api.brokerage.application.HaltScope.GLOBAL, null, "사고 대응", adminId = null)
        try {
            val before = jdbc.queryForObject("SELECT COUNT(*) FROM brokerage_orders WHERE user_id = ?", Long::class.java, userId)
            assertThatThrownBy { svc.submitOrder(userId, BrokerageOrderRequest(normalSymbol, "BUY", "MARKET", 1)) }
                .isInstanceOf(com.monticker.api.common.exception.TradingHaltedException::class.java)
                .hasMessageContaining("사고 대응")
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brokerage_orders WHERE user_id = ?", Long::class.java, userId))
                .isEqualTo(before)

            // 위험을 줄이는 방향(취소)은 막지 않는다
            assertThat(svc.cancelOrder(userId, resting.id).status).isEqualTo(BrokerageOrderStatus.CANCELLED)
        } finally {
            halts.lift(halt.id, "복구", adminId = null)
        }

        assertThat(svc.submitOrder(userId, BrokerageOrderRequest(normalSymbol, "BUY", "MARKET", 1)).status)
            .isEqualTo(BrokerageOrderStatus.FILLED)
    }

    // ── ADR-058 — 진행 중 매수가 노출에 잡힌다 ─────────────────────────────────────────────

    private fun stockIdOf(symbol: String) = jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, symbol)!!

    // Mock 증권사: 총평가 1억, 현재가 70,000 > 지정가 69,000 이라 체결되지 않고 SUBMITTED로 남는다. 360주 = 24.8%.
    private val quarter = BrokerageOrderRequest(normalSymbol, "BUY", "LIMIT", 360, BigDecimal("69000"))

    @Test
    fun `같은 종목 24·8% 지정가 매수 두 건 — 첫 건은 통과, 두 번째는 첫 건이 대기 노출로 잡혀 집중도 30% 한도에 막힌다`() {
        val svc = serviceWithRealRiskGate()
        val userId = seedUser()

        assertThat(svc.submitOrder(userId, quarter).status).isEqualTo(BrokerageOrderStatus.SUBMITTED)

        assertThatThrownBy { svc.submitOrder(userId, quarter) }
            .isInstanceOf(com.monticker.api.common.aop.RiskLimitException::class.java)
            .hasMessageContaining("ConcentrationRule")
        val detail = jdbc.queryForObject(
            "SELECT checks_json::text FROM risk_check_logs WHERE user_id = ? AND approved = false ORDER BY id DESC LIMIT 1", String::class.java, userId,
        )
        assertThat(detail).contains("대기 360")
        assertThat(stockIdOf(normalSymbol)).isPositive()
    }

    @Test
    fun `동시에 들어온 두 건도 한 건만 통과한다 — 준비 단계 직렬화로 두 번째가 첫 번째의 의도 행을 본다`() {
        // 두 번째가 준비할 때 첫 번째는 아직 PENDING_SUBMIT(증권사 호출 중)일 수도, 이미 SUBMITTED일 수도 있다.
        // 앞이면 ADR-056 중복 가드가, 뒤면 ADR-058 대기 노출(집중도)이 막는다. 어느 쪽이든 두 건이 함께 통과하지 않는다.
        val svc = serviceWithRealRiskGate()
        val userId = seedUser()
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Result<BrokerageOrder>> { start.await(); runCatching { svc.submitOrder(userId, quarter) } }
            }
            start.countDown()
            val outcomes = results.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }

            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOfAny(
                com.monticker.api.common.aop.RiskLimitException::class.java,
                com.monticker.api.common.exception.BusinessRuleException::class.java,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    // ── ADR-061 — 지정가 체결 자동 동기화, 정산은 하나 ──────────────────────────────────────

    @Test
    fun `지정가가 증권사에서 체결되면 동기화 잡이 FILLED와 정산을 남긴다 — 여러 경로가 동시에 반영해도 정산은 하나`() {
        val svc = service()
        val userId = seedUser()
        // 전용 종목 — 공유 종목에 낮은 캔들을 넣으면 같은 클래스의 다른 테스트의 지정가가 즉시 체결된다(실제로 그랬다)
        val syncSymbol = "SYNC01"
        jdbc.update("INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') ON CONFLICT DO NOTHING", syncSymbol, syncSymbol)
        fun candle(price: Int, minutesAhead: Int) = jdbc.update(
            """
            INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time)
            SELECT id, ?, ?, ?, ?, 1, date_trunc('minute', now()) + make_interval(mins => ?) FROM stocks WHERE symbol = ?
            ON CONFLICT DO NOTHING
            """.trimIndent(), price, price, price, price, minutesAhead, syncSymbol,
        )
        candle(70000, 0)
        val resting = svc.submitOrder(userId, BrokerageOrderRequest(syncSymbol, "BUY", "LIMIT", 5, BigDecimal("69000")))
        assertThat(resting.status).isEqualTo(BrokerageOrderStatus.SUBMITTED)

        // 시세가 지정가 아래로 내려왔다 — Mock 증권사는 다음 상태 조회 때 체결시킨다
        candle(68000, 1)

        // 동기화 잡 2개(레플리카) + 사용자의 "다시 확인"이 동시에
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val tasks = listOf(
                { svc.syncSubmittedOrder(resting.id) }, { svc.syncSubmittedOrder(resting.id) }, { svc.syncOrderStatus(userId, resting.id) },
            ).map { task -> pool.submit<Any?> { start.await(); runCatching { task() }.getOrNull() } }
            start.countDown()
            tasks.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }

        assertThat(row(resting.id)["status"]).isEqualTo("FILLED")
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brokerage_settlements WHERE order_id = ?", Long::class.java, resting.id))
            .isEqualTo(1L)
    }

    // ── ADR-062 — 실거래 일간 손실은 실현손익 ──────────────────────────────────────────────

    private fun filledOrder(userId: Long, side: String, qty: Int, fill: String, cost: String?, filledAt: String) {
        val accountId = jdbc.queryForObject("SELECT id FROM brokerage_accounts WHERE user_id = ?", Long::class.java, userId)
        jdbc.update(
            "INSERT INTO brokerage_orders (user_id, account_id, symbol, side, order_type, quantity, filled_qty, avg_fill_price, cost_basis_price, status, filled_at) " +
                "VALUES (?, ?, 'OTHER1', ?, 'MARKET', ?, ?, ?, ?, 'FILLED', $filledAt)",
            userId, accountId, side, qty, qty, BigDecimal(fill), cost?.let(::BigDecimal),
        )
    }

    private val todayKst8am = "(date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') + interval '8 hours') AT TIME ZONE 'Asia/Seoul'"

    @Test
    fun `하루에 크게 사도 매수가 막히지 않는다 — 예전엔 매수 대금이 손실로 잡혀 3% 넘게 사면 모든 매수가 막혔다`() {
        val svc = serviceWithRealRiskGate()
        val userId = seedUser()
        filledOrder(userId, "BUY", 200, "50000", null, todayKst8am)          // 오늘 1,000만원 매수(총평가 1억의 10%)

        assertThat(svc.submitOrder(userId, BrokerageOrderRequest(normalSymbol, "BUY", "MARKET", 1)).status)
            .isEqualTo(BrokerageOrderStatus.FILLED)
    }

    @Test
    fun `오늘(KST) 실현손실이 총평가액의 3%를 넘으면 막힌다 — KST 08시 체결(UTC로는 전날)도 오늘이다`() {
        val svc = serviceWithRealRiskGate()
        val userId = seedUser()
        filledOrder(userId, "SELL", 100, "50000", "90000", todayKst8am)        // 100 × (5만 − 9만) = −400만 > 300만 한도

        assertThatThrownBy { svc.submitOrder(userId, BrokerageOrderRequest(normalSymbol, "BUY", "MARKET", 1)) }
            .isInstanceOf(com.monticker.api.common.aop.RiskLimitException::class.java)
            .hasMessageContaining("DailyLossRule")
    }
}
