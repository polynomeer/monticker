package com.monticker.api.alert.application

import com.monticker.api.common.redis.RedisGuard
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import com.monticker.api.common.metrics.SearchMetrics
import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleConditions
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.alert.domain.AlertRuleType
import com.monticker.api.alert.infrastructure.AlertHistoryDocument
import com.monticker.api.alert.infrastructure.AlertHistorySearchRepository
import com.monticker.api.alert.infrastructure.AlertRuleRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class AlertService(
    private val alertRuleRepository: AlertRuleRepository,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val alertHistorySearchRepository: AlertHistorySearchRepository,
    private val esOps: ElasticsearchOperations,
    private val searchMetrics: SearchMetrics,
    private val redis: StringRedisTemplate,
    private val guard: RedisGuard,
) {
    companion object { const val ALERT_RULES_CHANGED_CHANNEL = "alert:rules:changed" }   // worker와 관례로 동기화
    private val log = LoggerFactory.getLogger(javaClass)
    /** 기본은 켜진 규칙만(모바일 등 기존 호출부). includePaused면 꺼 둔 규칙도(삭제한 것은 제외). */
    @Transactional(readOnly = true)
    fun getRules(userId: Long, includePaused: Boolean = false): List<AlertRule> =
        if (includePaused) alertRuleRepository.findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId)
        else alertRuleRepository.findAllByUserIdAndIsActiveTrue(userId)

    fun createRule(
        userId: Long,
        stockId: Long?,
        ruleType: AlertRuleType,
        condition: Map<String, Any>,
    ): AlertRule {
        val rule = AlertRule(
            userId = userId,
            stockId = stockId,
            ruleType = ruleType,
            conditionJson = objectMapper.writeValueAsString(condition),
        )
        val saved = alertRuleRepository.save(rule)
        publishChangedAfterCommit(stockId)
        return saved
    }

    /** DELETE — 규칙 삭제(ADR-073: 꺼지고 다시 켤 수 없다). */
    fun deactivateRule(userId: Long, ruleId: Long) {
        // 남의 규칙은 없는 규칙과 같은 404 — 400/403으로 구분하면 규칙 id 존재 여부를 열거할 수 있다
        val rule = alertRuleRepository.findById(ruleId).orElse(null)
            ?.takeIf { it.userId == userId }
            ?: throw NoSuchElementException("Alert rule not found: $ruleId")
        alertRuleRepository.markDeleted(ruleId, userId)
        publishChangedAfterCommit(rule.stockId)
    }

    /**
     * ADR-073 — 규칙 켜기/끄기. 삭제한 규칙·남의 규칙은 없는 것처럼 404. 다시 켤 때는 생성과 같은 조건 검사
     * (평가기가 없는 유형·조건 필드 누락이면 409)를 한다. 변경은 커밋 후 워커 인메모리 인덱스에 알린다(ADR-044).
     */
    fun setActive(userId: Long, ruleId: Long, active: Boolean): AlertRule {
        val rule = alertRuleRepository.findById(ruleId).orElse(null)
            ?.takeIf { it.userId == userId && !it.isDeleted }
            ?: throw NoSuchElementException("알림 규칙을 찾을 수 없습니다: $ruleId")
        if (rule.isActive == active) return rule
        if (active) {
            val condition: Map<String, Any?> = runCatching {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(rule.conditionJson, Map::class.java) as Map<String, Any?>
            }.getOrDefault(emptyMap())
            if (!AlertRuleConditions.isValid(rule.ruleType, rule.stockId, condition)) {
                throw BusinessRuleException("이 알림 규칙은 다시 켤 수 없습니다(지원하지 않는 조건). 새 규칙을 만들어 주세요")
            }
        }
        // 조건부 UPDATE — 읽은 뒤 다른 요청이 삭제했으면 0행이라 되살리지 않는다
        if (alertRuleRepository.updateActive(ruleId, userId, active) == 0) {
            throw NoSuchElementException("알림 규칙을 찾을 수 없습니다: $ruleId")
        }
        publishChangedAfterCommit(rule.stockId)
        return alertRuleRepository.findById(ruleId).orElseThrow()
    }

    // ── ADR-073 읽음 상태 ──────────────────────────────────────────────────

    /** 이력 id들의 읽음 시각(내 규칙의 이력만). 없는 id는 결과에 없다. */
    @Transactional(readOnly = true)
    fun readStates(userId: Long, historyIds: Collection<Long>): Map<Long, Instant?> {
        if (historyIds.isEmpty()) return emptyMap()
        val ids = historyIds.distinct().take(200)
        val placeholders = ids.joinToString(",") { "?" }
        return jdbc.query(
            """
            SELECT ah.id, ah.read_at FROM alert_histories ah
            JOIN alert_rules ar ON ar.id = ah.rule_id
            WHERE ar.user_id = ? AND ah.id IN ($placeholders)
            """,
            { rs, _ -> rs.getLong("id") to rs.getTimestamp("read_at")?.toInstant() },
            *(listOf<Any>(userId) + ids).toTypedArray(),
        ).toMap()
    }

    /** 이력 한 건 읽음. 이미 읽었으면 그대로(멱등). 내 이력이 아니면 404. */
    fun markRead(userId: Long, historyId: Long) {
        val updated = jdbc.update(
            """
            UPDATE alert_histories ah SET read_at = now()
            FROM alert_rules ar
            WHERE ah.id = ? AND ar.id = ah.rule_id AND ar.user_id = ? AND ah.read_at IS NULL
            """,
            historyId, userId,
        )
        if (updated == 0 && readStates(userId, listOf(historyId)).isEmpty()) {
            throw NoSuchElementException("알림을 찾을 수 없습니다: $historyId")
        }
    }

    /** [upTo] 이전에 발동한 내 알림을 모두 읽음으로. 화면을 연 뒤 새로 온 알림까지 읽음 처리하지 않게 시각을 받는다. */
    fun markAllRead(userId: Long, upTo: Instant): Int = jdbc.update(
        """
        UPDATE alert_histories ah SET read_at = now()
        FROM alert_rules ar
        WHERE ar.id = ah.rule_id AND ar.user_id = ? AND ah.read_at IS NULL AND ah.triggered_at <= ?
        """,
        userId, java.sql.Timestamp.from(upTo),
    )

    /**
     * ADR-044 — 워커의 인메모리 룰 인덱스에 변경을 알린다. 커밋 후에만 발행한다(롤백된 변경이 전파되면 안 된다).
     * Redis pub/sub은 at-most-once라 워커가 5분 주기로 updated_at 기준 보정 재로드를 한다 — 여기서 실패해도
     * 최대 5분 지연일 뿐이므로 fail-open. 이 서비스가 룰의 유일한 쓰기 경로라 발행 지점도 여기 한 곳이다.
     */
    private fun publishChangedAfterCommit(stockId: Long?) {
        if (stockId == null) return
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { publishChanged(stockId); return }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = publishChanged(stockId)
        })
    }

    private fun publishChanged(stockId: Long) {
        guard.failOpen(op = "alert_rules_changed_publish", fallback = Unit) {
            redis.convertAndSend(ALERT_RULES_CHANGED_CHANNEL, stockId.toString()); Unit
        }
    }

    @Transactional(readOnly = true)
    fun getStats(userId: Long): AlertStatsResponse {
        val counts = jdbc.queryForMap("""
            SELECT
              COUNT(*) FILTER (WHERE ah.delivery_status = 'SENT')    AS sent,
              COUNT(*) FILTER (WHERE ah.delivery_status = 'FAILED')  AS failed,
              COUNT(*) FILTER (WHERE ah.delivery_status = 'PENDING') AS pending
            FROM alert_histories ah
            JOIN alert_rules ar ON ar.id = ah.rule_id
            WHERE ar.user_id = ?
        """, userId)

        val sent    = (counts["sent"]    as? Number)?.toInt() ?: 0
        val failed  = (counts["failed"]  as? Number)?.toInt() ?: 0
        val pending = (counts["pending"] as? Number)?.toInt() ?: 0
        val total   = sent + failed + pending
        val rate    = if (sent + failed > 0) sent.toDouble() / (sent + failed) * 100 else 0.0

        val unread = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM alert_histories ah
            JOIN alert_rules ar ON ar.id = ah.rule_id
            WHERE ar.user_id = ? AND ah.read_at IS NULL
            """,
            Int::class.java, userId
        ) ?: 0

        val activeRules = jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_rules WHERE user_id = ? AND is_active = true",
            Int::class.java, userId
        ) ?: 0

        val daily = jdbc.query("""
            SELECT DATE(ah.triggered_at AT TIME ZONE 'Asia/Seoul') AS d, COUNT(*) AS cnt
            FROM alert_histories ah
            JOIN alert_rules ar ON ar.id = ah.rule_id
            WHERE ar.user_id = ? AND ah.triggered_at > NOW() - INTERVAL '7 days'
            GROUP BY d ORDER BY d
        """, { rs, _ -> AlertFireStat(rs.getString("d"), rs.getInt("cnt")) }, userId)

        return AlertStatsResponse(total, sent, failed, rate, activeRules, daily, unread)
    }

    /**
     * 내 알림 이력을 키워드·종목·타입·상태·날짜로 검색 (ES).
     */
    @Transactional(readOnly = true)
    fun searchHistory(
        userId: Long,
        query: String? = null,
        stockId: Long? = null,
        ruleType: String? = null,
        deliveryStatus: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 20,
    ): List<AlertHistoryResult> {
        return try {
            val nativeQuery = NativeQuery.builder()
                .withQuery { q ->
                    q.bool { b ->
                        b.filter { f -> f.term { t -> t.field("userId").value(userId) } }
                        if (!query.isNullOrBlank()) {
                            b.must { m ->
                                m.match { mm ->
                                    mm.field("message").query(query).analyzer("nori_analyzer")
                                }
                            }
                        }
                        if (stockId != null) {
                            b.filter { f -> f.term { t -> t.field("stockId").value(stockId) } }
                        }
                        if (!ruleType.isNullOrBlank()) {
                            b.filter { f -> f.term { t -> t.field("ruleType").value(ruleType) } }
                        }
                        if (!deliveryStatus.isNullOrBlank()) {
                            b.filter { f -> f.term { t -> t.field("deliveryStatus").value(deliveryStatus) } }
                        }
                        if (from != null || to != null) {
                            b.filter { f ->
                                f.range { r ->
                                    r.date { d ->
                                        var dr = d.field("triggeredAt")
                                        if (from != null) dr = dr.gte(from.toEpochMilli().toString())
                                        if (to != null)   dr = dr.lte(to.toEpochMilli().toString())
                                        dr
                                    }
                                }
                            }
                        }
                        b
                    }
                }
                .withMaxResults(limit.coerceIn(1, 100))
                .build()

            val hits = esOps.search(nativeQuery, AlertHistoryDocument::class.java)
                .map { hit -> AlertHistoryResult.from(hit.content, hit.score) }
                .toList()
            // 읽음 상태는 DB가 원본이다(ADR-073). ES 문서에는 없다.
            val reads = readStates(userId, hits.map { it.id })
            hits.map { it.copy(readAt = reads[it.id]) }
        } catch (e: Exception) {
            searchMetrics.fallback("alert_histories")
            log.warn("ES alert history search failed for userId={}: {}", userId, e.message)
            emptyList()
        }
    }
}

data class AlertStatsResponse(
    val totalFired: Int, val totalSent: Int, val totalFailed: Int,
    val successRate: Double, val activeRules: Int,
    val recentFires: List<AlertFireStat>,
    /** ADR-073 — 읽지 않은 알림 수 */
    val unread: Int = 0,
)
data class AlertFireStat(val date: String, val count: Int)

data class AlertHistoryResult(
    val id: Long,
    val ruleId: Long,
    val stockId: Long?,
    val ruleType: String,
    val message: String,
    val deliveryStatus: String,
    val triggeredAt: Instant,
    val score: Float?,
    /** ADR-073 — DB(alert_histories.read_at)에서 붙인다. null이면 읽지 않음 */
    val readAt: Instant? = null,
) {
    companion object {
        fun from(doc: AlertHistoryDocument, score: Float) = AlertHistoryResult(
            id             = doc.id.toLong(),
            ruleId         = doc.ruleId,
            stockId        = doc.stockId,
            ruleType       = doc.ruleType,
            message        = doc.message,
            deliveryStatus = doc.deliveryStatus,
            triggeredAt    = doc.triggeredAt,
            score          = score,
        )
    }
}
