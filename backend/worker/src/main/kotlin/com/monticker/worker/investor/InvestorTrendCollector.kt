package com.monticker.worker.investor

import com.monticker.worker.common.DistributedLock
import com.monticker.worker.kis.KisClient
import com.monticker.worker.kis.KisInvestorDay
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.sql.Date as SqlDate

private data class StockRow(val id: Long, val symbol: String)

/**
 * 개인/외국인/기관 순매수(투자자 동향) 일일 배치 — ADR-017.
 * KRX 데이터라 KOSPI/KOSDAQ 종목만 대상. 장마감 이후 1회 수집한다.
 */
@Component
class InvestorTrendCollector(
    private val kisClient: KisClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 16:00 (장마감 15:30 이후 여유를 두고) 실행
    @Scheduled(cron = "0 0 16 * * MON-FRI")
    @DistributedLock(name = "investor-trend-collector", ttlSeconds = 1800)
    fun collect() {
        val stocks = jdbc.query(
            "SELECT id, symbol FROM stocks WHERE is_active = true AND market IN ('KOSPI', 'KOSDAQ')",
        ) { rs, _ -> StockRow(rs.getLong("id"), rs.getString("symbol")) }

        log.info("Starting investor-trend sync for {} KRX stocks...", stocks.size)
        var synced = 0
        var mocked = 0

        for (stock in stocks) {
            val days = kisClient.fetchInvestorTrend(stock.symbol)
            if (!days.isNullOrEmpty()) {
                upsert(stock.id, days, isMocked = false)
                synced++
            } else {
                upsert(stock.id, listOf(mockDay(stock.id)), isMocked = true)
                mocked++
            }
        }

        log.info("Investor-trend sync complete: synced={}, mocked={}, total={}", synced, mocked, stocks.size)
    }

    private fun upsert(stockId: Long, days: List<KisInvestorDay>, isMocked: Boolean) {
        for (d in days) {
            jdbc.update(
                """
                INSERT INTO investor_flow (
                    stock_id, trade_date,
                    individual_net_qty, foreign_net_qty, institution_net_qty,
                    individual_net_amount, foreign_net_amount, institution_net_amount,
                    is_mocked
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_id, trade_date) DO UPDATE SET
                    individual_net_qty     = EXCLUDED.individual_net_qty,
                    foreign_net_qty        = EXCLUDED.foreign_net_qty,
                    institution_net_qty    = EXCLUDED.institution_net_qty,
                    individual_net_amount  = EXCLUDED.individual_net_amount,
                    foreign_net_amount     = EXCLUDED.foreign_net_amount,
                    institution_net_amount = EXCLUDED.institution_net_amount,
                    is_mocked               = EXCLUDED.is_mocked
                """,
                stockId, SqlDate.valueOf(d.tradeDate),
                d.individualNetQty, d.foreignNetQty, d.institutionNetQty,
                d.individualNetAmount, d.foreignNetAmount, d.institutionNetAmount,
                isMocked,
            )
        }
    }

    /**
     * KIS 미설정/응답 없음 시 폴백 — StockMasterCollector와 동일하게 결정적 의사난수로 생성.
     * 실데이터가 아님을 API 응답의 isMocked 필드로 프론트에 명시한다 (InvestorFlowService 참고).
     */
    private fun mockDay(stockId: Long): KisInvestorDay {
        val seed = (stockId * 31 + LocalDate.now().toEpochDay()) % 2001 - 1000 // -1000..1000
        return KisInvestorDay(
            tradeDate            = LocalDate.now(),
            individualNetQty     = -seed * 120,
            foreignNetQty        = seed * 80,
            institutionNetQty    = seed * 40,
            individualNetAmount  = -seed * 12_000_000,
            foreignNetAmount     = seed * 8_000_000,
            institutionNetAmount = seed * 4_000_000,
        )
    }
}
