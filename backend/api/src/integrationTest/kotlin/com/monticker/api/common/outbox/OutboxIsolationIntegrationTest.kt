package com.monticker.api.common.outbox

import com.monticker.api.common.notification.NotificationCategory
import com.monticker.api.common.notification.UserNotificationCommand
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-094 — api의 Outbox(Modulith JPA 레지스트리, public.event_publication)가 worker의 발행 기록과 분리돼 있음을
 * **실제 Postgres + 실제 Modulith 레지스트리 + 운영 application.yml**로 보인다.
 *
 * 2026-10-08 로컬 스택에서 확인된 결함: 두 앱이 같은 테이블을 쓰면서 api 재전송이 worker의 SearchIndexEvent 행을 만나
 * "Unable to locate named class"로 통째로 실패했고, Kafka 장애 중 기록된 UserNotificationCommand가 끝내 재전송되지
 * 않았다. 여기서는 그 알림 명령이 리스너 실패로 미완료로 남은 뒤 1분이 지나면 재전송되는지를 본다.
 *
 * 컨텍스트는 Outbox에 필요한 자동 구성만 띄운다(Kafka 외부화 대신 실패를 흉내 낼 수 있는 테스트 리스너).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxIsolationIntegrationTest : PostgresIntegrationTest() {

    @Configuration(proxyBeanMethods = false)
    @ImportOutboxAutoConfiguration
    @Import(OutboxResubmissionConfig::class, OutboxCompletedCleanup::class, FlakyNotificationListener::class)
    class OutboxTestApp

    /** Kafka 외부화 자리를 대신한다 — [failuresLeft]만큼 실패해 발행 기록을 미완료로 남긴다 */
    @Component   // kotlin-spring이 열어 준다 — Modulith가 완료 기록용 프록시를 씌운다
    class FlakyNotificationListener {
        val failuresLeft = AtomicInteger(0)
        val received = CopyOnWriteArrayList<UserNotificationCommand>()

        @TransactionalEventListener
        fun on(command: UserNotificationCommand) {
            received += command
            if (failuresLeft.getAndDecrement() > 0) throw IllegalStateException("Kafka down (simulated)")
        }
    }

    private lateinit var context: ConfigurableApplicationContext
    private val listener get() = context.getBean(FlakyNotificationListener::class.java)
    private val publisher: ApplicationEventPublisher get() = context   // 운영 서비스가 주입받는 것과 같은 퍼블리셔
    private val tx get() = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))

    @BeforeAll
    fun startContext() {
        dataSource   // 컨테이너 기동 + Flyway(V91 포함)
        context = SpringApplicationBuilder(OutboxTestApp::class.java)
            .web(WebApplicationType.NONE)
            // 명령행 인자 — 운영 application.yml(${DB_URL:…})보다 우선한다. properties()는 기본값이라 yml에 진다.
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.flyway.enabled=false",
            )
    }

    @AfterAll
    fun stopContext() = context.close()

    @BeforeEach
    fun clean() {
        jdbcTemplate.update("DELETE FROM event_publication")
        jdbcTemplate.update("DELETE FROM worker_outbox.event_publication")
        listener.received.clear()
        listener.failuresLeft.set(0)
    }

    private fun command(dedup: String = UUID.randomUUID().toString()) = UserNotificationCommand(
        userId = 42L, category = NotificationCategory.entries.first(), title = "주문 결과 확인 필요", body = "결과 불명", dedupKey = dedup,
    )

    /** 리스너가 실패해 미완료로 남은 알림 명령 하나를 만들고, 재전송 대상이 되도록 2분 전으로 돌린다 */
    private fun stuckNotification(): UserNotificationCommand {
        val cmd = command()
        listener.failuresLeft.set(1)
        runCatching { tx.executeWithoutResult { publisher.publishEvent(cmd) } }
        jdbcTemplate.update("UPDATE event_publication SET publication_date = now() - INTERVAL '2 minutes' WHERE completion_date IS NULL")
        return cmd
    }

    private fun insertRaw(table: String, eventType: String) = jdbcTemplate.update(
        """
        INSERT INTO $table (id, listener_id, event_type, serialized_event, publication_date)
        VALUES (?, 'org.springframework.modulith.events.support.DelegatingEventExternalizer.externalize(java.lang.Object)', ?, '{}',
                now() - INTERVAL '10 minutes')
        """.trimIndent(),
        UUID.randomUUID(), eventType,
    )

    private fun incompleteApiRows() =
        jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Long::class.java)

    @Test
    fun `an api notification left incomplete for over a minute is resubmitted and completed`() {
        val cmd = stuckNotification()
        assertThat(incompleteApiRows()).isEqualTo(1)

        context.getBean(OutboxResubmissionConfig::class.java).resubmit()

        assertThat(listener.received).containsExactly(cmd, cmd)   // 최초 실패 + 재전송
        assertThat(incompleteApiRows()).isZero()
    }

    @Test
    fun `api resubmission succeeds while the worker has its own incomplete rows`() {
        val cmd = stuckNotification()
        // 새 worker가 기록한 미완료 행 — api 프로세스에 없는 클래스다
        insertRaw("worker_outbox.event_publication", "com.monticker.worker.search.SearchIndexEvent")

        context.getBean(OutboxResubmissionConfig::class.java).resubmit()

        assertThat(listener.received).containsExactly(cmd, cmd)
        assertThat(incompleteApiRows()).isZero()
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM worker_outbox.event_publication WHERE completion_date IS NULL", Long::class.java,
        )).isEqualTo(1)   // api는 worker 행을 건드리지 않는다
    }

    @Test
    fun `a worker row in the api table still breaks the whole resubmission — the reason the tables are split`() {
        stuckNotification()
        insertRaw("event_publication", "com.monticker.worker.search.SearchIndexEvent")

        // V91 이전의 공유 테이블 상태. 이 실패가 알림 유실의 원인이었다 — 그래서 V91/LegacyOutboxDrain이 이런 행을 옮긴다.
        assertThatThrownBy { context.getBean(OutboxResubmissionConfig::class.java).resubmit() }
            .hasStackTraceContaining("com.monticker.worker.search.SearchIndexEvent")
        assertThat(listener.received).hasSize(1)   // 알림은 재전송되지 못했다
    }

    @Test
    fun `the publication row commits and rolls back with the business transaction`() {
        listener.failuresLeft.set(0)
        runCatching {
            tx.executeWithoutResult {
                publisher.publishEvent(command())
                throw IllegalStateException("business failure")
            }
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication", Long::class.java)).isZero()
        assertThat(listener.received).isEmpty()

        tx.executeWithoutResult { publisher.publishEvent(command()) }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM event_publication WHERE completion_date IS NOT NULL", Long::class.java,
        )).isEqualTo(1)
    }

    @Test
    fun `completed rows older than the retention are purged, younger and incomplete ones stay`() {
        val old = UUID.randomUUID()
        val young = UUID.randomUUID()
        val incomplete = UUID.randomUUID()
        val sql = """
            INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
            VALUES (?, 'l', ?, '{}', now() - CAST(? AS INTERVAL), now() - CAST(? AS INTERVAL))
        """.trimIndent()
        // 구버전 worker가 공유 테이블에 남긴 완료 행도 클래스 해석 없이 함께 지워져야 한다
        jdbcTemplate.update(sql, old, "com.monticker.worker.search.SearchIndexEvent", "9 days", "8 days")
        jdbcTemplate.update(sql, young, UserNotificationCommand::class.java.name, "2 days", "2 days")
        jdbcTemplate.update(
            "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date) VALUES (?, 'l', ?, '{}', now() - INTERVAL '30 days')",
            incomplete, UserNotificationCommand::class.java.name,
        )

        context.getBean(OutboxCompletedCleanup::class.java).purge()

        assertThat(jdbcTemplate.queryForList("SELECT id FROM event_publication", UUID::class.java))
            .containsExactlyInAnyOrder(young, incomplete)
    }
}

/** 자동 구성 목록은 `META-INF/spring/com.monticker.api.common.outbox.ImportOutboxAutoConfiguration.imports` */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ImportAutoConfiguration
annotation class ImportOutboxAutoConfiguration
