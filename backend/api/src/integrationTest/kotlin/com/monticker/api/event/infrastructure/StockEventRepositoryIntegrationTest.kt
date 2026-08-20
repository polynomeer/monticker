package com.monticker.api.event.infrastructure

import com.monticker.api.event.domain.EventType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * stock_events는 이벤트 타임라인 API가 유일하게 읽는 중심 테이블이다
 * (docs: backend-architect — "stock_events is the central table... The timeline
 * API reads only from it"). StockEventRepository의 시간 범위 WHERE 절, 정렬,
 * 그리고 중복 방지용 exists 체크는 목 기반 단위 테스트(EventTimelineServiceTest)에서는
 * 검증되지 않는다 — Spring Data가 생성한 실제 쿼리가 실제 Postgres 스키마(Flyway로
 * 마이그레이션됨) 위에서 옳게 동작하는지는 실제 DB 없이는 알 수 없다.
 *
 * 행은 JPA가 아닌 JdbcTemplate으로 직접 삽입한다 — 실제로 stock_events에 쓰는 쪽은
 * worker 모듈의 EventDetector(raw JDBC)이고, backend/api는 오직 조회만 한다
 * (grep으로 확인: src/main/kotlin 어디서도 stockEventRepository.save(...)를 호출하지
 * 않는다). 이 방식은 실제 쓰기 경로를 그대로 재현하면서, StockEvent 엔티티의
 * metadataJson(JSONB) 컬럼에 Hibernate가 null 값을 바인딩할 때 타입을 문자열로
 * 추론해 "column is of type jsonb but expression is of type character varying"
 * 에러를 내는, 이 테스트의 목적과 무관한 별도의 엔티티 매핑 이슈를 피해간다.
 *
 * 또한 V5__create_stock_events.sql의 uq_stock_events_dedup 유니크 인덱스
 * (stock_id, event_type, date_trunc('minute', event_time))가 실제로 같은
 * 분(minute) 버킷 내 중복 이벤트를 막는지도 여기서 검증한다 — 이는
 * event-detector-reviewer.md가 요구하는 "event generation must be idempotent"
 * 계약이 DB 레벨에서 실제로 지켜지는지에 대한 증거다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class StockEventRepositoryIntegrationTest {

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

    @Autowired
    private lateinit var stockEventRepository: StockEventRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun createStock(symbol: String): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO stocks (symbol, name, market, exchange)
            VALUES (?, ?, 'KOSPI', 'KRX')
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            symbol, "종목-$symbol",
        )!!

    private fun insertEvent(stockId: Long, type: EventType, time: Instant, title: String = "test") {
        jdbcTemplate.update(
            """
            INSERT INTO stock_events (stock_id, event_type, title, event_time, importance_score)
            VALUES (?, ?, ?, ?, 0)
            """.trimIndent(),
            stockId, type.name, title, Timestamp.from(time),
        )
    }

    @Test
    fun `findByStockIdAndTimeRange returns only events within range for the requested stock, newest first`() {
        val stockA = createStock("AAA001")
        val stockB = createStock("BBB002")
        val now = Instant.now()

        insertEvent(stockA, EventType.PRICE_SPIKE, now.minus(1, ChronoUnit.HOURS))
        insertEvent(stockA, EventType.VOLUME_SURGE, now.minus(30, ChronoUnit.MINUTES))
        insertEvent(stockA, EventType.PRICE_DROP, now.minus(48, ChronoUnit.HOURS)) // outside the range
        insertEvent(stockB, EventType.PRICE_SPIKE, now.minus(20, ChronoUnit.MINUTES)) // different stock

        val result = stockEventRepository.findByStockIdAndTimeRange(
            stockA, now.minus(2, ChronoUnit.HOURS), now,
        )

        assertThat(result).hasSize(2)
        assertThat(result.map { it.eventType }).containsExactly(EventType.VOLUME_SURGE, EventType.PRICE_SPIKE)
    }

    @Test
    fun `existsByStockIdAndEventTypeAndEventTimeBetween finds an event only for the matching stock and type`() {
        val stockA = createStock("CCC003")
        val bucketStart = Instant.parse("2026-06-30T09:00:00Z")
        val bucketEnd = bucketStart.plus(5, ChronoUnit.MINUTES)
        insertEvent(stockA, EventType.VOLUME_SURGE, bucketStart.plusSeconds(30))

        val existsForMatchingType = stockEventRepository.existsByStockIdAndEventTypeAndEventTimeBetween(
            stockA, EventType.VOLUME_SURGE, bucketStart, bucketEnd,
        )
        val existsForDifferentType = stockEventRepository.existsByStockIdAndEventTypeAndEventTimeBetween(
            stockA, EventType.PRICE_SPIKE, bucketStart, bucketEnd,
        )
        val existsOutsideWindow = stockEventRepository.existsByStockIdAndEventTypeAndEventTimeBetween(
            stockA, EventType.VOLUME_SURGE, bucketEnd, bucketEnd.plus(5, ChronoUnit.MINUTES),
        )

        assertThat(existsForMatchingType).isTrue()
        assertThat(existsForDifferentType).isFalse()
        assertThat(existsOutsideWindow).isFalse()
    }

    @Test
    fun `findByOrderByEventTimeDesc returns exactly the number of recent events the Pageable requests`() {
        // Regression guard: this method must NOT be named with Spring Data's "Top"
        // keyword (e.g. findTopByOrderByEventTimeDesc) — "Top" without a following
        // digit silently caps the result at 1 row regardless of the Pageable's page
        // size, which broke GET /api/events/recent's `limit` parameter in production.
        val stockA = createStock("DDD004")
        val stockB = createStock("EEE005")
        val stockC = createStock("FFF006")
        val now = Instant.now()

        insertEvent(stockA, EventType.PRICE_SPIKE, now.minus(3, ChronoUnit.HOURS))
        insertEvent(stockB, EventType.VOLUME_SURGE, now.minus(1, ChronoUnit.HOURS))
        insertEvent(stockC, EventType.NEWS_PUBLISHED, now)

        val result = stockEventRepository.findByOrderByEventTimeDesc(PageRequest.of(0, 2))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.stockId }).containsExactly(stockC, stockB)
    }

    @Test
    fun `the DB-level unique index rejects a duplicate stock-type event within the same minute bucket`() {
        // Guards the idempotency contract from event-detector-reviewer.md: duplicate events
        // for the same (stock_id, event_type, time_bucket) must be prevented at write time.
        val stockA = createStock("GGG007")
        val bucket = Instant.parse("2026-06-30T09:15:00Z")

        insertEvent(stockA, EventType.VOLUME_SURGE, bucket)

        assertThatThrownBy {
            insertEvent(stockA, EventType.VOLUME_SURGE, bucket.plusSeconds(20)) // same UTC minute bucket
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
