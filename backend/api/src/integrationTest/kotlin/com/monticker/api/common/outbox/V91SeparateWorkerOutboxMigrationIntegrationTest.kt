package com.monticker.api.common.outbox

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * ADR-094 — V91이 worker 소유 미완료 행만 worker_outbox로 옮기고, 구버전 앱이 쓰는 public.event_publication의
 * 구조는 건드리지 않는지 본다. V90까지 올린 별도 데이터베이스에 공유 테이블 시절의 행을 깔고 V91을 적용한다.
 */
class V91SeparateWorkerOutboxMigrationIntegrationTest : PostgresIntegrationTest() {

    private val workerType = "com.monticker.worker.search.SearchIndexEvent"
    private val apiType = "com.monticker.api.common.notification.UserNotificationCommand"

    private fun freshDatabase(): DriverManagerDataSource {
        dataSource   // 컨테이너 기동
        val name = "v91_" + UUID.randomUUID().toString().replace("-", "").take(12)
        jdbcTemplate.execute("CREATE DATABASE $name")
        val url = postgres.jdbcUrl.replace("/monticker", "/$name")
        return DriverManagerDataSource(url, postgres.username, postgres.password).apply { setDriverClassName(postgres.driverClassName) }
    }

    private fun migrate(ds: DriverManagerDataSource, target: String?) {
        Flyway.configure()
            .dataSource(ds)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))   // V84 CONCURRENTLY — 세션 lock
            .apply { if (target != null) target(target) }
            .load()
            .migrate()
    }

    private fun columns(jdbc: JdbcTemplate) = jdbc.queryForList(
        """
        SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'event_publication' ORDER BY ordinal_position
        """.trimIndent(),
        String::class.java,
    )

    @Test
    fun `moves only incomplete worker rows older than a minute and leaves the shared table's shape intact`() {
        val ds = freshDatabase()
        migrate(ds, "90")
        val jdbc = JdbcTemplate(ds)
        val before = columns(jdbc)

        val insert = """
            INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
            VALUES (?, 'listener', ?, ?, now() - CAST(? AS INTERVAL), ?::timestamptz)
        """.trimIndent()
        val workerStuck = UUID.randomUUID()
        val workerInFlight = UUID.randomUUID()
        val workerDone = UUID.randomUUID()
        val apiStuck = UUID.randomUUID()
        jdbc.update(insert, workerStuck, workerType, """{"docId":"1"}""", "3 hours", null)
        jdbc.update(insert, workerInFlight, workerType, """{"docId":"2"}""", "5 seconds", null)
        jdbc.update(insert, workerDone, workerType, """{"docId":"3"}""", "2 days", "2026-10-01T00:00:00Z")
        jdbc.update(insert, apiStuck, apiType, """{"userId":42}""", "3 hours", null)

        migrate(ds, null)

        assertThat(jdbc.queryForList("SELECT id FROM event_publication", UUID::class.java))
            // 막 기록된(1분 미만) worker 행은 구버전 worker가 지금 완료 표시할 수 있어 남긴다 — 새 worker의 LegacyOutboxDrain이 옮긴다
            .containsExactlyInAnyOrder(workerInFlight, workerDone, apiStuck)
        val moved = jdbc.queryForMap("SELECT * FROM worker_outbox.event_publication")
        assertThat(moved["id"]).isEqualTo(workerStuck)
        assertThat(moved["event_type"]).isEqualTo(workerType)
        assertThat(moved["serialized_event"]).isEqualTo("""{"docId":"1"}""")
        assertThat(moved["listener_id"]).isEqualTo("listener")
        assertThat(moved["completion_date"]).isNull()
        assertThat(jdbc.queryForObject(
            "SELECT publication_date < now() - INTERVAL '2 hours' FROM worker_outbox.event_publication", Boolean::class.java,
        )).isTrue()   // 원래 기록 시각을 유지해야 재전송 대상(1분 경과)이 된다

        // 구버전 api(JPA ddl-auto=validate)·worker가 V91 뒤에도 그대로 쓸 수 있다
        assertThat(columns(jdbc)).isEqualTo(before)
    }
}
