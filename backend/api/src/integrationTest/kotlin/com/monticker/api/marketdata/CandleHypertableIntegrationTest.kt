package com.monticker.api.marketdata

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-041 — V42가 실제 TimescaleDB 컨테이너에서 캔들 테이블을 hypertable로 승격하고 압축 정책을 걸었는지.
 *
 * 이 테스트가 없으면 언젠가 또 조용히 미가동 상태로 돌아간다 — ADR-002의 "Flyway가 create_hypertable()을
 * 호출해야 한다"는 문장이 정확히 그렇게 됐다(한 번도 이행되지 않은 채 문서만 남았다).
 */
class CandleHypertableIntegrationTest : PostgresIntegrationTest() {

    @Test
    fun `candles_1m 과 candles_1d 는 hypertable 이다`() {
        val names = jdbcTemplate.queryForList(
            "SELECT hypertable_name FROM timescaledb_information.hypertables", String::class.java)
        assertThat(names).contains("candles_1m", "candles_1d")
    }

    @Test
    fun `압축 정책이 stock_id segmentby 로 걸려 있고 compress_after 가 upsert 윈도우보다 뒤다`() {
        val settings = jdbcTemplate.queryForList(
            "SELECT hypertable_name, segmentby_column_index FROM timescaledb_information.compression_settings WHERE attname = 'stock_id'")
        assertThat(settings.map { it["hypertable_name"] }).contains("candles_1m", "candles_1d")

        // add_compression_policy → jobs 테이블의 config.compress_after
        val policies = jdbcTemplate.queryForList(
            """SELECT hypertable_name, (config->>'compress_after') AS after
               FROM timescaledb_information.jobs WHERE proc_name = 'policy_compression'""")
            .associate { it["hypertable_name"] as String to it["after"] as String }
        assertThat(policies["candles_1m"]).contains("14 days")
        assertThat(policies["candles_1d"]).contains("90 days")   // ADR-021의 당일 upsert 계약
    }

    @Test
    fun `price_ticks 는 더 이상 존재하지 않는다 — 한 번도 쓰인 적 없던 테이블`() {
        val exists = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'price_ticks'", Int::class.java)
        assertThat(exists).isZero()
    }

    @Test
    fun `승격 후에도 CandleAggregator 의 upsert 형태가 동작한다`() {
        // 압축되지 않은(최근) chunk에 ON CONFLICT DO UPDATE — flush()가 쓰는 문장 그대로
        val now = Timestamp.from(Instant.now())
        repeat(2) {
            jdbcTemplate.update(
                """INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume)
                   VALUES (999999, ?, 1, 1, 1, 1, 10)
                   ON CONFLICT (stock_id, candle_time) DO UPDATE SET
                     high = GREATEST(candles_1m.high, EXCLUDED.high), close = EXCLUDED.close,
                     volume = candles_1m.volume + EXCLUDED.volume""", now)
        }
        val volume = jdbcTemplate.queryForObject(
            "SELECT volume FROM candles_1m WHERE stock_id = 999999 AND candle_time = ?", Long::class.java, now)
        assertThat(volume).isEqualTo(20L)
        jdbcTemplate.update("DELETE FROM candles_1m WHERE stock_id = 999999")
    }
}
