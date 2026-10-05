package com.monticker.worker.marketdata

import org.springframework.beans.factory.ObjectProvider
import io.mockk.mockk
import io.mockk.every
import com.monticker.worker.toss.TossExecutionTickSubscriber
import com.monticker.worker.kis.KisWebSocketClient
import com.monticker.worker.kis.KisCoverageProvider
import com.monticker.worker.support.PostgresIntegrationTest
import com.monticker.worker.toss.TossCoverageProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager

/** ADR-060 — 커버리지 공표가 실제 스키마(api 마이그레이션 V53)에서 "집합 전체 덮어쓰기"로 동작하는지 확인한다. */
class RealtimeCoveragePublisherIntegrationTest : PostgresIntegrationTest() {

    private fun publisher(
        kisKeys: Boolean, tossKeys: Boolean, source: String = "kis,toss",
        kisConnected: Boolean = true, tossConnected: Boolean = true,
    ): RealtimeCoveragePublisher {
        val kis = KisCoverageProvider(jdbcTemplate, source, if (kisKeys) "k" else "", if (kisKeys) "s" else "")
        val toss = TossCoverageProvider(jdbcTemplate, source, kis, if (tossKeys) "k" else "", if (tossKeys) "s" else "")
        val ws = mockk<KisWebSocketClient> { every { isConnected } returns kisConnected }
        val sub = mockk<TossExecutionTickSubscriber> { every { connectedStockIds() } returns if (tossConnected) toss.coveredStockIds else emptySet() }
        val provider = mockk<ObjectProvider<TossExecutionTickSubscriber>> { every { ifAvailable } returns sub }
        return RealtimeCoveragePublisher(jdbcTemplate, kis, toss, DataSourceTransactionManager(dataSource), ws, provider)
    }

    private fun published(): Map<String, Int> =
        jdbcTemplate.queryForList("SELECT source, COUNT(*) AS n FROM realtime_price_coverage GROUP BY source")
            .associate { it["source"] as String to (it["n"] as Number).toInt() }

    @Test
    fun `구성된 실시세 집합을 공표하고, 다시 공표하면 빠진 종목은 지워지며, 실시세가 꺼지면 비워진다`() {
        publisher(kisKeys = true, tossKeys = true).publish()
        val both = published()
        assertThat(both["KIS"]).isEqualTo(KisCoverageProvider.MAX_TICK_SYMBOLS)
        assertThat(both["TOSS"]).isPositive()

        // Toss 키가 빠졌다 — Toss 종목은 더 이상 실시세가 없다
        publisher(kisKeys = true, tossKeys = false).publish()
        assertThat(published()).isEqualTo(mapOf("KIS" to KisCoverageProvider.MAX_TICK_SYMBOLS))

        // ingestion.source=internal(기본 배포) — 실시세 0종목
        publisher(kisKeys = true, tossKeys = true, source = "internal").publish()
        assertThat(published()).isEmpty()
    }

    @Test
    fun `선언된 집합이 아니라 연결이 살아 있는 집합을 공표한다 — 웹소켓이 끊기면 그 종목은 커버리지에서 빠진다`() {
        publisher(kisKeys = true, tossKeys = true, kisConnected = false).publish()
        assertThat(published()["KIS"]).isNull()
        assertThat(published()["TOSS"]).isPositive()

        publisher(kisKeys = true, tossKeys = true, kisConnected = true, tossConnected = false).publish()
        assertThat(published()).isEqualTo(mapOf("KIS" to KisCoverageProvider.MAX_TICK_SYMBOLS))
    }

    @Test
    fun `KIS 키 없이 kis를 켜면 커버리지가 비어 Mock 생성에서도 빠지지 않는다 — 시세가 멈추지 않게`() {
        val kis = KisCoverageProvider(jdbcTemplate, "kis", "", "")
        assertThat(kis.coveredStockIds).isEmpty()
    }
}
