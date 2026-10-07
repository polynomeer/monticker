package com.monticker.api.event.application

import com.monticker.api.common.cache.CacheConfig
import com.monticker.api.event.domain.EventType
import com.monticker.api.event.infrastructure.EventAggregateRepository
import com.monticker.api.event.infrastructure.EventTypeTally
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 유형별 건수 — eventType은 서버 EventType 이름 */
data class EventTypeCount(val eventType: String, val count: Long, val stockCount: Long)

/**
 * 하루(KST) 이벤트 집계. 캐시(Redis)에 그대로 들어가므로 필드는 문자열·숫자·목록만 쓴다(ADR-087).
 * - total: 모든 유형 건수 합
 * - surgeStocks / plungeStocks: 그날 PRICE_SPIKE / PRICE_DROP 이벤트가 하나라도 난 종목 수
 *   (같은 종목이 하루에 여러 번 급등 이벤트를 내도 한 번으로 센다 — 건수는 byType에 있다)
 */
data class EventDaySummary(
    val date: String,
    val total: Long,
    val byType: List<EventTypeCount>,
    val surgeStocks: Long,
    val plungeStocks: Long,
)

data class StockEventCount(val stockId: Long, val count: Long)

/** 검증·정규화를 마친 종목별 건수 요청. 캐시 키가 순서·중복에 흔들리지 않게 ids는 정렬돼 있다 */
data class EventCountQuery(val stockIds: List<Long>, val days: Int, val today: LocalDate) {
    val from: Instant get() = EventAggregateService.dayStart(today.minusDays(days.toLong()))
    val to: Instant get() = EventAggregateService.dayStart(today.plusDays(1))
    val cacheKey: String get() = "${stockIds.joinToString(",")}:$days:$today"
}

/**
 * 이벤트 집계(ADR-087) — 홈 상단 "오늘 이벤트"·"급등·급락", /compare "이벤트 수".
 *
 * 하루의 경계는 KST 자정(`LocalDate.atStartOfDay(Asia/Seoul)`)을 Instant로 바꾼 값이고, SQL에는
 * timestamptz 반열림 구간 `[from, to)`로만 넘긴다. JVM·DB 세션 타임존과 무관하다.
 */
@Service
class EventAggregateService(private val repo: EventAggregateRepository) {

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        /** 요약을 조회할 수 있는 가장 먼 과거(일) */
        const val MAX_SUMMARY_LOOKBACK_DAYS = 366L
        /** 한 요청의 종목 수 상한 — /compare는 4종목 */
        const val MAX_COUNT_STOCKS = 20
        /** 기간 상한(일) — /compare 3년 */
        const val MAX_COUNT_DAYS = 1095

        fun today(now: Instant = Instant.now()): LocalDate = LocalDate.ofInstant(now, KST)

        /** KST 날짜의 자정 → Instant */
        fun dayStart(date: LocalDate): Instant = date.atStartOfDay(KST).toInstant()

        /** null이면 오늘(KST). 미래나 [MAX_SUMMARY_LOOKBACK_DAYS]보다 먼 과거면 IllegalArgumentException(400) */
        fun resolveSummaryDate(date: LocalDate?, now: Instant = Instant.now()): LocalDate {
            val today = today(now)
            val d = date ?: today
            require(!d.isAfter(today)) { "미래 날짜는 조회할 수 없습니다: $d" }
            require(!d.isBefore(today.minusDays(MAX_SUMMARY_LOOKBACK_DAYS))) {
                "최근 ${MAX_SUMMARY_LOOKBACK_DAYS}일 이내 날짜만 조회할 수 있습니다"
            }
            return d
        }

        /** "1,2,3" → 검증·정렬된 요청. 잘못된 값이면 IllegalArgumentException(400) */
        fun parseCountQuery(rawIds: String, days: Int, now: Instant = Instant.now()): EventCountQuery {
            val tokens = rawIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            require(tokens.isNotEmpty()) { "stockIds가 비어 있습니다" }
            // 정규화 전에도 상한을 둔다 — 같은 id를 수천 번 반복한 요청을 파싱하느라 일하지 않게
            require(tokens.size <= MAX_COUNT_STOCKS * 2) { "종목은 최대 ${MAX_COUNT_STOCKS}개까지 조회할 수 있습니다" }
            val ids = tokens.map { t ->
                val id = t.toLongOrNull()
                require(id != null && id > 0) { "잘못된 종목 ID: $t" }
                id
            }.distinct().sorted()
            require(ids.size <= MAX_COUNT_STOCKS) { "종목은 최대 ${MAX_COUNT_STOCKS}개까지 조회할 수 있습니다" }
            require(days in 1..MAX_COUNT_DAYS) { "days는 1 ~ $MAX_COUNT_DAYS 입니다" }
            return EventCountQuery(ids, days, today(now))
        }

        /** DB 집계를 응답 모양으로 — 모든 EventType을 0으로라도 채워 화면이 키 유무를 따지지 않게 한다 */
        fun assemble(date: LocalDate, tallies: List<EventTypeTally>): EventDaySummary {
            val byName = tallies.associateBy { it.eventType }
            val known = EventType.entries.map { t ->
                val x = byName[t.name]
                EventTypeCount(t.name, x?.count ?: 0, x?.stockCount ?: 0)
            }
            // enum에 없는 유형이 DB에 있으면(다른 모듈이 먼저 쓰기 시작한 경우) 버리지 않고 뒤에 붙인다
            val unknown = tallies.filter { t -> EventType.entries.none { it.name == t.eventType } }
                .sortedBy { it.eventType }
                .map { EventTypeCount(it.eventType, it.count, it.stockCount) }
            val all = known + unknown
            return EventDaySummary(
                date         = date.toString(),
                total        = all.sumOf { it.count },
                byType       = all,
                surgeStocks  = byName[EventType.PRICE_SPIKE.name]?.stockCount ?: 0,
                plungeStocks = byName[EventType.PRICE_DROP.name]?.stockCount ?: 0,
            )
        }
    }

    /** 하루(KST) 이벤트 유형별 집계. date는 [resolveSummaryDate]를 거친 값 */
    @Cacheable(cacheNames = [CacheConfig.EVENT_SUMMARY], key = "#date.toString()")
    fun summary(date: LocalDate): EventDaySummary =
        assemble(date, repo.countByType(dayStart(date), dayStart(date.plusDays(1))))

    /** 종목별 기간 이벤트 수. 요청한 종목은 모두(0건 포함) 요청 순서(정렬)대로 돌려준다 */
    @Cacheable(cacheNames = [CacheConfig.EVENT_COUNTS], key = "#query.cacheKey")
    fun counts(query: EventCountQuery): List<StockEventCount> {
        val counts = repo.countByStock(query.stockIds, query.from, query.to)
        return query.stockIds.map { StockEventCount(it, counts[it] ?: 0) }
    }
}
