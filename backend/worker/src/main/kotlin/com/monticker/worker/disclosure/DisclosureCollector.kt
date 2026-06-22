package com.monticker.worker.disclosure

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class DisclosureCollector(
    private val dartClient: DartClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    @Scheduled(fixedDelay = 600_000) // 10분마다
    fun collect() {
        val disclosures = if (dartClient.isConfigured) {
            dartClient.fetchRecent(days = 1)
        } else {
            MockDisclosureData.generate()
        }

        if (disclosures.isEmpty()) return

        // 종목코드 → stock_id 맵 빌드
        val symbolToId = fetchStockIds(disclosures.map { it.stockCode }.distinct())

        var saved = 0
        for (d in disclosures) {
            val stockId = symbolToId[d.stockCode] ?: continue
            val eventTime = parseEventTime(d.rceptDate)
            val inserted = insertEvent(stockId, d, eventTime)
            if (inserted) saved++
        }

        if (saved > 0 || dartClient.isConfigured) {
            log.info("Disclosure sync: processed={} saved={} configured={}",
                disclosures.size, saved, dartClient.isConfigured)
        }
    }

    private fun fetchStockIds(symbols: List<String>): Map<String, Long> {
        if (symbols.isEmpty()) return emptyMap()
        val placeholders = symbols.joinToString(",") { "?" }
        return jdbc.query(
            "SELECT id, symbol FROM stocks WHERE symbol IN ($placeholders)",
            { rs, _ -> rs.getString("symbol") to rs.getLong("id") },
            *symbols.toTypedArray(),
        ).toMap()
    }

    private fun insertEvent(stockId: Long, d: DartDisclosure, eventTime: Instant): Boolean {
        // dedup: 같은 rcept_no는 1번만
        val exists = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM stock_events
            WHERE stock_id = ? AND source_type = 'DART'
              AND metadata_json->>'rceptNo' = ?
            """,
            Int::class.java,
            stockId,
            d.rceptNo,
        ) ?: 0
        if (exists > 0) return false

        val importance = importanceScore(d.reportName)
        val meta = mapper.writeValueAsString(
            mapOf("rceptNo" to d.rceptNo, "reportName" to d.reportName)
        )

        jdbc.update(
            """
            INSERT INTO stock_events
              (stock_id, event_type, title, description, event_time,
               importance_score, source_type, metadata_json, created_at, updated_at)
            VALUES (?, 'DISCLOSURE_PUBLISHED', ?, ?, ?, ?, 'DART', ?::jsonb, now(), now())
            """,
            stockId,
            "[공시] ${d.reportName}",
            "${d.corpName} — ${d.reportName}",
            Timestamp.from(eventTime),
            importance,
            meta,
        )
        return true
    }

    private fun importanceScore(reportName: String): Int = when {
        reportName.contains("사업보고서") || reportName.contains("반기보고서")         -> 80
        reportName.contains("분기보고서")                                              -> 70
        reportName.contains("유상증자") || reportName.contains("자기주식")             -> 85
        reportName.contains("임원") || reportName.contains("주요주주")                 -> 60
        reportName.contains("합병") || reportName.contains("분할") || reportName.contains("인수") -> 90
        else                                                                          -> 50
    }

    private fun parseEventTime(rceptDate: String): Instant =
        runCatching {
            LocalDate.parse(rceptDate, dateFmt)
                .atStartOfDay(ZoneId.of("Asia/Seoul"))
                .toInstant()
        }.getOrDefault(Instant.now())
}
