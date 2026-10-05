package com.monticker.api.screener.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.Statement
import java.time.Instant

data class SavedScreenRow(
    val id: Long,
    val userId: Long,
    val name: String,
    val criteriaJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** ADR-072 — saved_screens. 모든 조회·수정은 user_id 조건을 함께 건다(남의 행을 id만으로 건드리지 못하게). */
@Repository
class SavedScreenRepository(private val jdbc: JdbcTemplate) {

    companion object {
        /** pg_advisory_xact_lock 첫 번째 키 — 사용자별 저장 개수 상한 검사를 직렬화한다(BrokerageService는 56_001). */
        const val ADVISORY_NS_SAVED_SCREEN = 72_001
    }

    private val mapper = { rs: java.sql.ResultSet, _: Int ->
        SavedScreenRow(
            id           = rs.getLong("id"),
            userId       = rs.getLong("user_id"),
            name         = rs.getString("name"),
            criteriaJson = rs.getString("criteria"),
            createdAt    = rs.getTimestamp("created_at").toInstant(),
            updatedAt    = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    /** 같은 트랜잭션 안에서 이 사용자의 저장 스크린 변경을 직렬화한다 */
    fun lockUser(userId: Long) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, (? % 2147483647)::int)", { _ -> }, ADVISORY_NS_SAVED_SCREEN, userId)
    }

    fun findAll(userId: Long): List<SavedScreenRow> =
        jdbc.query("SELECT * FROM saved_screens WHERE user_id = ? ORDER BY created_at, id", mapper, userId)

    fun find(userId: Long, id: Long): SavedScreenRow? =
        jdbc.query("SELECT * FROM saved_screens WHERE id = ? AND user_id = ?", mapper, id, userId).firstOrNull()

    fun count(userId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM saved_screens WHERE user_id = ?", Int::class.java, userId) ?: 0

    fun existsName(userId: Long, name: String, exceptId: Long? = null): Boolean =
        (jdbc.queryForObject(
            "SELECT COUNT(*) FROM saved_screens WHERE user_id = ? AND name = ? AND id <> ?",
            Int::class.java, userId, name, exceptId ?: -1L,
        ) ?: 0) > 0

    fun insert(userId: Long, name: String, criteriaJson: String): Long {
        val keys = GeneratedKeyHolder()
        jdbc.update({ con ->
            con.prepareStatement(
                "INSERT INTO saved_screens (user_id, name, criteria) VALUES (?, ?, ?::jsonb)",
                Statement.RETURN_GENERATED_KEYS,
            ).apply { setLong(1, userId); setString(2, name); setString(3, criteriaJson) }
        }, keys)
        return (keys.keys?.get("id") as Number).toLong()
    }

    fun update(userId: Long, id: Long, name: String, criteriaJson: String): Boolean =
        jdbc.update(
            "UPDATE saved_screens SET name = ?, criteria = ?::jsonb, updated_at = now() WHERE id = ? AND user_id = ?",
            name, criteriaJson, id, userId,
        ) == 1

    fun delete(userId: Long, id: Long): Boolean =
        jdbc.update("DELETE FROM saved_screens WHERE id = ? AND user_id = ?", id, userId) == 1
}
