package com.monticker.worker.outbox

import com.monticker.worker.newsalert.NewsAlertCandidateEvent
import com.monticker.worker.newsalert.NewsAlertKind
import com.monticker.worker.newsalert.NewsAlertNotifyEvent
import com.monticker.worker.search.SearchIndexEvent
import com.monticker.worker.support.PostgresIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-094 — worker Outbox(Modulith JDBC 레지스트리, worker_outbox.event_publication)가 api의 발행 기록과 분리돼
 * 있음을 **실제 Postgres(api 마이그레이션 V91까지) + 실제 Modulith 레지스트리 + 운영 application.yml**로 보인다.
 *
 * 2026-10-08 로컬 스택: 공유 테이블에서 worker 재전송이 api의 UserNotificationCommand 행을 만나 통째로 실패했다.
 * 여기서는 그 행이 public에 있어도 worker 재전송이 자기 행을 끝까지 처리하는지, 발행 기록이 JPA 트랜잭션과 함께
 * 커밋·롤백되는지(JDBC 레지스트리로 바꿨어도 Outbox 보장이 유지되는지), 전환용 이관·정리·기동 가드를 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerOutboxIsolationIntegrationTest : PostgresIntegrationTest() {

    @Configuration(proxyBeanMethods = false)
    @ImportOutboxAutoConfiguration
    @Import(
        OutboxResubmissionConfig::class, LegacyOutboxDrain::class, OutboxCompletedCleanup::class,
        WorkerOutboxSchemaGuard::class, FlakySearchIndexListener::class, FlakyNewsAlertListener::class,
    )
    class OutboxTestApp {
        @Bean fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    /** Kafka 외부화 자리를 대신한다 — [failuresLeft]만큼 실패해 발행 기록을 미완료로 남긴다 */
    @Component   // kotlin-spring이 열어 준다 — Modulith가 완료 기록용 프록시를 씌운다
    class FlakySearchIndexListener {
        val failuresLeft = AtomicInteger(0)
        val received = CopyOnWriteArrayList<SearchIndexEvent>()

        @TransactionalEventListener
        fun on(event: SearchIndexEvent) {
            received += event
            if (failuresLeft.getAndDecrement() > 0) throw IllegalStateException("Kafka down (simulated)")
        }
    }

    /** ADR-100 — 뉴스 알림 내부 이벤트가 발행 기록(JSON)을 거쳐 재전송될 때 그대로 되살아나는지 본다 */
    @Component
    class FlakyNewsAlertListener {
        val failuresLeft = AtomicInteger(0)
        val candidates = CopyOnWriteArrayList<NewsAlertCandidateEvent>()
        val notifies = CopyOnWriteArrayList<NewsAlertNotifyEvent>()

        @TransactionalEventListener
        fun on(event: NewsAlertCandidateEvent) {
            candidates += event
            if (failuresLeft.getAndDecrement() > 0) throw IllegalStateException("fan-out failed (simulated)")
        }

        @TransactionalEventListener
        fun on(event: NewsAlertNotifyEvent) {
            notifies += event
            if (failuresLeft.getAndDecrement() > 0) throw IllegalStateException("delivery failed (simulated)")
        }
    }

    private lateinit var context: ConfigurableApplicationContext
    private val listener get() = context.getBean(FlakySearchIndexListener::class.java)
    private val publisher: ApplicationEventPublisher get() = context
    private val tx get() = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))

    private fun args() = arrayOf(
        // 명령행 인자 — 운영 application.yml(${DB_URL:…})보다 우선한다
        "--spring.datasource.url=${postgres.jdbcUrl}",
        "--spring.datasource.username=${postgres.username}",
        "--spring.datasource.password=${postgres.password}",
    )

    @BeforeAll
    fun startContext() {
        dataSource   // 컨테이너 기동 + api 마이그레이션(V91 포함)
        context = SpringApplicationBuilder(OutboxTestApp::class.java).web(WebApplicationType.NONE).run(*args())
    }

    @AfterAll
    fun stopContext() = context.close()

    @BeforeEach
    fun clean() {
        jdbcTemplate.update("DELETE FROM public.event_publication")
        jdbcTemplate.update("DELETE FROM worker_outbox.event_publication")
        listener.received.clear()
        listener.failuresLeft.set(0)
        newsListener.candidates.clear()
        newsListener.notifies.clear()
        newsListener.failuresLeft.set(0)
    }

    private val newsListener get() = context.getBean(FlakyNewsAlertListener::class.java)

    private fun event() = SearchIndexEvent.index("news", UUID.randomUUID().toString(), mapOf("title" to "삼성전자 공시"))

    private fun count(sql: String) = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun insertRaw(table: String, eventType: String, age: String) = UUID.randomUUID().also {
        jdbcTemplate.update(
            """
            INSERT INTO $table (id, listener_id, event_type, serialized_event, publication_date)
            VALUES (?, 'org.springframework.modulith.events.support.DelegatingEventExternalizer.externalize(java.lang.Object)', ?, '{}',
                    now() - CAST(? AS INTERVAL))
            """.trimIndent(),
            it, eventType, age,
        )
    }

    @Test
    fun `the worker records publications in its own table, committed and rolled back with the business transaction`() {
        runCatching {
            tx.executeWithoutResult {
                publisher.publishEvent(event())
                throw IllegalStateException("business failure")
            }
        }
        assertThat(count("SELECT count(*) FROM worker_outbox.event_publication")).isZero()

        tx.executeWithoutResult { publisher.publishEvent(event()) }

        assertThat(count("SELECT count(*) FROM worker_outbox.event_publication WHERE completion_date IS NOT NULL")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM public.event_publication")).isZero()   // api 테이블에는 쓰지 않는다
    }

    @Test
    fun `worker resubmission succeeds while an api notification is stuck in the api table`() {
        val apiRow = insertRaw("public.event_publication", "com.monticker.api.common.notification.UserNotificationCommand", "10 minutes")
        val stuck = event()
        listener.failuresLeft.set(1)
        runCatching { tx.executeWithoutResult { publisher.publishEvent(stuck) } }
        jdbcTemplate.update("UPDATE worker_outbox.event_publication SET publication_date = now() - INTERVAL '2 minutes'")
        assertThat(count("SELECT count(*) FROM worker_outbox.event_publication WHERE completion_date IS NULL")).isEqualTo(1)

        context.getBean(OutboxResubmissionConfig::class.java).resubmit()

        assertThat(listener.received).containsExactly(stuck, stuck)   // 최초 실패 + 재전송
        assertThat(count("SELECT count(*) FROM worker_outbox.event_publication WHERE completion_date IS NULL")).isZero()
        assertThat(jdbcTemplate.queryForList("SELECT id FROM public.event_publication WHERE completion_date IS NULL", UUID::class.java))
            .containsExactly(apiRow)   // api 행은 그대로 — api가 재전송한다
    }

    @Test
    fun `rows an old worker wrote to the shared table are drained over and resubmitted`() {
        // 구버전 worker가 공유 테이블에 남긴 실제 형태의 행 — 리스너 id는 이 테스트 리스너로 맞춘다
        val stuck = event()
        listener.failuresLeft.set(1)
        runCatching { tx.executeWithoutResult { publisher.publishEvent(stuck) } }
        jdbcTemplate.update(
            """
            WITH moved AS (DELETE FROM worker_outbox.event_publication RETURNING *)
            INSERT INTO public.event_publication SELECT id, listener_id, event_type, serialized_event, now() - INTERVAL '3 minutes', completion_date FROM moved
            """.trimIndent(),
        )
        val fresh = insertRaw("public.event_publication", SearchIndexEvent::class.java.name, "5 seconds")
        val apiRow = insertRaw("public.event_publication", "com.monticker.api.common.notification.UserNotificationCommand", "10 minutes")

        context.getBean(OutboxResubmissionConfig::class.java).resubmit()

        assertThat(listener.received).containsExactly(stuck, stuck)
        assertThat(count("SELECT count(*) FROM worker_outbox.event_publication WHERE completion_date IS NOT NULL")).isEqualTo(1)
        // 1분 미만(구버전 worker가 지금 완료 표시할 수 있는 행)과 api 행은 남는다
        assertThat(jdbcTemplate.queryForList("SELECT id FROM public.event_publication", UUID::class.java))
            .containsExactlyInAnyOrder(fresh, apiRow)
    }

    @Test
    fun `news alert events survive a failed first delivery and come back intact on resubmission`() {
        val candidate = NewsAlertCandidateEvent(
            kind = NewsAlertKind.DISCLOSURE, sourceId = 7L, stockId = 3L, title = "[공시] 합병 결정",
            publishedAtMillis = 1_760_000_000_000L, importanceScore = 90,
        )
        val notify = NewsAlertNotifyEvent(
            userId = 11L, historyId = 99L, title = "삼성전자 새 공시", body = "[공시] 합병 결정", dedupKey = "disclosure:7:u11",
            data = mapOf("type" to "NEWS", "kind" to "DISCLOSURE"),
        )
        newsListener.failuresLeft.set(2)
        runCatching { tx.executeWithoutResult { publisher.publishEvent(candidate); publisher.publishEvent(notify) } }
        jdbcTemplate.update("UPDATE worker_outbox.event_publication SET publication_date = now() - INTERVAL '2 minutes'")
        assertThat(count("SELECT count(*) FROM worker_outbox.event_publication WHERE completion_date IS NULL")).isEqualTo(2)

        context.getBean(OutboxResubmissionConfig::class.java).resubmit()

        assertThat(newsListener.candidates).containsExactly(candidate, candidate)
        assertThat(newsListener.notifies).containsExactly(notify, notify)
        assertThat(newsListener.notifies.last().toMessage().category).isEqualTo("NEWS")
        assertThat(count("SELECT count(*) FROM worker_outbox.event_publication WHERE completion_date IS NULL")).isZero()
    }

    @Test
    fun `completed rows older than the retention are purged, younger and incomplete ones stay`() {
        val sql = """
            INSERT INTO worker_outbox.event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
            VALUES (?, 'l', ?, '{}', now() - CAST(? AS INTERVAL), now() - CAST(? AS INTERVAL))
        """.trimIndent()
        val old = UUID.randomUUID().also { jdbcTemplate.update(sql, it, SearchIndexEvent::class.java.name, "9 days", "8 days") }
        val young = UUID.randomUUID().also { jdbcTemplate.update(sql, it, SearchIndexEvent::class.java.name, "2 days", "2 days") }
        val incomplete = insertRaw("worker_outbox.event_publication", SearchIndexEvent::class.java.name, "30 days")

        context.getBean(OutboxCompletedCleanup::class.java).purge()

        assertThat(jdbcTemplate.queryForList("SELECT id FROM worker_outbox.event_publication", UUID::class.java))
            .containsExactlyInAnyOrder(young, incomplete)
            .doesNotContain(old)
    }

    @Test
    fun `the worker refuses to start when the api has not created its outbox table yet`() {
        assertThatThrownBy {
            SpringApplicationBuilder(OutboxTestApp::class.java).web(WebApplicationType.NONE)
                .run(*args(), "--spring.modulith.events.jdbc.schema=not_migrated_yet")
                .close()
        }.hasStackTraceContaining("api를 먼저 배포")
    }
}

/** 자동 구성 목록은 `META-INF/spring/com.monticker.worker.outbox.ImportOutboxAutoConfiguration.imports` */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ImportAutoConfiguration
annotation class ImportOutboxAutoConfiguration
