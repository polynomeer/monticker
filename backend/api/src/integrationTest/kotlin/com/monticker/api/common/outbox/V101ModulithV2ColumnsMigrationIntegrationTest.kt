package com.monticker.api.common.outbox

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * V101(docs/dependency-upgrade-plan.md B4-2) — Spring Modulith 2의 event publication v2 컬럼이 api·worker 두 아웃박스에
 * nullable로 들어가고, 그 컬럼을 모르는 지금의 Modulith 1.4식 INSERT가 그대로 동작하는지 본다.
 * 실제 Modulith 레지스트리가 이 스키마 위에서 도는 것은 [OutboxIsolationIntegrationTest]가 본다.
 */
class V101ModulithV2ColumnsMigrationIntegrationTest : PostgresIntegrationTest() {

    private fun columns(schema: String): Map<String, Pair<String, String>> =
        jdbcTemplate.query(
            """
            SELECT column_name, data_type, is_nullable FROM information_schema.columns
            WHERE table_schema = ? AND table_name = 'event_publication'
              AND column_name IN ('status', 'completion_attempts', 'last_resubmission_date')
            """.trimIndent(),
            { rs, _ -> rs.getString(1) to (rs.getString(2) to rs.getString(3)) },
            schema,
        ).toMap()

    @Test
    fun `api·worker 두 아웃박스에 v2 컬럼이 nullable로 있다`() {
        val expected = mapOf(
            "status" to ("text" to "YES"),
            "completion_attempts" to ("integer" to "YES"),
            "last_resubmission_date" to ("timestamp with time zone" to "YES"),
        )
        assertThat(columns("public")).isEqualTo(expected)
        assertThat(columns("worker_outbox")).isEqualTo(expected)
    }

    @Test
    fun `v2 컬럼을 모르는 Modulith 1·4식 INSERT가 그대로 들어가고 status는 NULL로 남는다`() {
        for (table in listOf("event_publication", "worker_outbox.event_publication")) {
            val id = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO $table (id, listener_id, event_type, serialized_event, publication_date) VALUES (?, 'l', 'T', '{}', ?)",
                id, Timestamp.from(Instant.now()),
            )
            // Modulith 2.1.1의 재전송 대상 쿼리: status = FAILED or (status is null and completion_date is null) — 이 행이 걸린다
            val pickedUp = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM $table WHERE id = ? AND (status = 'FAILED' OR (status IS NULL AND completion_date IS NULL))",
                Long::class.java, id,
            )
            assertThat(pickedUp).describedAs(table).isEqualTo(1L)
            jdbcTemplate.update("DELETE FROM $table WHERE id = ?", id)
        }
    }
}
