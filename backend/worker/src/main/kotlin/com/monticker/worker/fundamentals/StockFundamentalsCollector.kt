package com.monticker.worker.fundamentals

import com.monticker.worker.common.DistributedLock
import com.monticker.worker.kis.KisClient
import com.monticker.worker.kis.KisPrice
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

private data class StockRow(val id: Long, val symbol: String)
private data class MockFundamentals(
    val marketCap: Long,
    val per: BigDecimal,
    val pbr: BigDecimal,
    val eps: BigDecimal,
    val bps: BigDecimal,
)

/**
 * 시가총액/PER/PBR/EPS/BPS 일일 스냅샷 배치 — ADR-018.
 * KIS inquire-price 응답에 이미 포함된 필드를 재사용하므로 신규 벤더 연동 없음.
 * KRX 데이터라 KOSPI/KOSDAQ 종목만 대상. 장마감 이후, InvestorTrendCollector(16:00) 다음에 수집한다.
 */
@Component
class StockFundamentalsCollector(
    private val kisClient: KisClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 16 * * MON-FRI")
    @DistributedLock(name = "stock-fundamentals-collector", ttlSeconds = 1800)
    fun collect() {
        val stocks = jdbc.query(
            "SELECT id, symbol FROM stocks WHERE is_active = true AND market IN ('KOSPI', 'KOSDAQ')",
        ) { rs, _ -> StockRow(rs.getLong("id"), rs.getString("symbol")) }

        log.info("Starting stock-fundamentals sync for {} KRX stocks...", stocks.size)
        var synced = 0
        var mocked = 0

        for (stock in stocks) {
            val price = kisClient.fetchPrice(stock.symbol)
            if (price != null && price.marketCapEok != null) {
                upsert(stock.id, price, isMocked = false)
                synced++
            } else {
                upsert(stock.id, mockFundamentals(stock.id), isMocked = true)
                mocked++
            }
        }

        log.info("Stock-fundamentals sync complete: synced={}, mocked={}, total={}", synced, mocked, stocks.size)
    }

    // 앱 시작 시 1회 실행 — 비어있으면 즉시 채워서 스크리너 시가총액 필터가 배포 당일 종일 빈 결과로 보이지 않게 한다.
    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    fun collectOnStartup() {
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM stock_fundamentals", Int::class.java) ?: 0
        if (count == 0) {
            log.info("stock_fundamentals table is empty — running initial sync")
            collect()
        } else {
            log.info("stock_fundamentals table has {} rows — skipping initial sync", count)
        }
    }

    private fun upsert(stockId: Long, price: KisPrice, isMocked: Boolean) {
        jdbc.update(
            """
            INSERT INTO stock_fundamentals (stock_id, market_cap, per, pbr, eps, bps, is_mocked, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (stock_id) DO UPDATE SET
                market_cap = EXCLUDED.market_cap,
                per        = EXCLUDED.per,
                pbr        = EXCLUDED.pbr,
                eps        = EXCLUDED.eps,
                bps        = EXCLUDED.bps,
                is_mocked  = EXCLUDED.is_mocked,
                updated_at = now()
            """,
            stockId,
            // hts_avls는 KIS 관례상 억원 단위 — 원 단위로 환산해 저장 (프론트 AmountLabel이 원 단위 기준).
            // 실제 KIS 키로 미검증된 가정이므로 실전 연동 전 재확인할 것.
            price.marketCapEok?.let { it * 100_000_000L },
            price.per, price.pbr, price.eps, price.bps,
            isMocked,
        )
    }

    private fun upsert(stockId: Long, mock: MockFundamentals, isMocked: Boolean) {
        jdbc.update(
            """
            INSERT INTO stock_fundamentals (stock_id, market_cap, per, pbr, eps, bps, is_mocked, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (stock_id) DO UPDATE SET
                market_cap = EXCLUDED.market_cap,
                per        = EXCLUDED.per,
                pbr        = EXCLUDED.pbr,
                eps        = EXCLUDED.eps,
                bps        = EXCLUDED.bps,
                is_mocked  = EXCLUDED.is_mocked,
                updated_at = now()
            """,
            stockId, mock.marketCap, mock.per, mock.pbr, mock.eps, mock.bps, isMocked,
        )
    }

    /**
     * KIS 미설정/응답 없음 시 폴백 — 대형/중형/소형 세 구간에 고르게 분산되도록
     * stockId % 3으로 구간을 고정해 결정적으로 생성한다 (로컬 개발에서 시가총액 필터
     * 세 구간을 다 테스트할 수 있도록). 실데이터가 아님을 is_mocked로 프론트에 명시.
     */
    private fun mockFundamentals(stockId: Long): MockFundamentals {
        val spread = ((stockId * 37 + LocalDate.now().toEpochDay()) % 1000)
        val marketCap = when ((stockId % 3).toInt()) {
            0    -> 1_000_000_000_000L + spread * 49_000_000_000L  // 대형: 1조 ~ 50조
            1    -> 100_000_000_000L   + spread * 900_000_000L     // 중형: 1000억 ~ 1조
            else -> 10_000_000_000L    + spread * 90_000_000L      // 소형: 100억 ~ 1000억
        }
        return MockFundamentals(
            marketCap = marketCap,
            per       = BigDecimal(5 + spread % 40).setScale(2),
            pbr       = BigDecimal(5 + spread % 30).divide(BigDecimal.TEN).setScale(2),
            eps       = BigDecimal(500 + spread % 5000),
            bps       = BigDecimal(3000 + spread % 30000),
        )
    }
}
