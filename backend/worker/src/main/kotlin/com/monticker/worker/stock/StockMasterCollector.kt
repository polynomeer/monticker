package com.monticker.worker.stock

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class StockMasterCollector(
    private val krxClient: KrxStockClient,
    private val jdbc: JdbcTemplate,
    /**
     * 운영 스위치(STOCK_MASTER_SYNC_ENABLED, 기본 꺼짐). upsert가 없는 컬럼을 써서 이 동기화는 지금까지 한 번도 반영된 적이 없다 —
     * 고친 채로 켜면 첫 실행에 KRX 전 종목(약 2,700개)이 한꺼번에 들어온다(봉·차트 없는 종목, 검색·스크리너 결과 변화).
     * 그래서 지금까지와 같은 상태(사실상 꺼짐)를 기본값으로 두고, 켜는 건 배포 체크리스트에서 정한다.
     */
    @Value("\${stock-master.sync-enabled:false}") private val enabled: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 오전 6시 실행
    @Scheduled(cron = "0 0 6 * * *")
    fun collect() {
        if (!enabled) {
            log.debug("Stock master sync disabled (stock-master.sync-enabled=false)")
            return
        }
        log.info("Starting stock master sync...")
        val stocks = krxClient.fetchStocks().ifEmpty {
            log.warn("KRX returned empty — falling back to mock data")
            MockStockData.stocks
        }
        upsert(stocks)
    }

    // 앱 시작 시 1회 실행 (DB에 종목이 5개 이하면 즉시 적재)
    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    fun collectOnStartup() {
        if (!enabled) return
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM stocks", Int::class.java) ?: 0
        if (count <= 5) {
            log.info("Stocks table has only $count rows — running initial sync")
            collect()
        } else {
            log.info("Stocks table has $count rows — skipping initial sync")
        }
    }

    private fun upsert(stocks: List<KrxStockItem>) {
        var upserted = 0
        var moved    = 0

        for (s in stocks) {
            val (market, exchange, country, currency) = when (s.market) {
                "KOSPI"  -> listOf("KOSPI",  "KRX",    "KR", "KRW")
                "KOSDAQ" -> listOf("KOSDAQ", "KRX",    "KR", "KRW")
                "NASDAQ" -> listOf("NASDAQ", "NASDAQ", "US", "USD")
                "NYSE"   -> listOf("NYSE",   "NYSE",   "US", "USD")
                else     -> listOf(s.market, s.market, "KR", "KRW")
            }

            // stocks에는 updated_at 컬럼이 없다(V1). 예전엔 DO UPDATE에 `updated_at = now()`가 있어 이 문장이 매번 SQL 오류였고,
            // 첫 종목에서 collect()가 끝나 매일 06시 동기화가 한 번도 반영되지 않았다(신규 상장·종목명 변경 누락).
            upserted += jdbc.update(
                """
                INSERT INTO stocks (symbol, name, market, exchange, sector, country, currency, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, true)
                ON CONFLICT (symbol, market) DO UPDATE SET
                    name      = EXCLUDED.name,
                    sector    = EXCLUDED.sector,
                    is_active = true
                """,
                s.symbol, s.name, market, exchange, s.sector, country, currency,
            )
            // 이전상장(KOSDAQ → KOSPI 등): 유니크 키가 (symbol, market)이라 새 시장 행이 따로 생기고 옛 행이 활성으로 남는다.
            // KRX 종목코드는 시장을 넘어 하나뿐이므로 다른 국내 시장의 같은 코드 행은 끈다 — 남겨 두면 코드로 종목을 찾는 곳마다
            // 어느 행인지 갈리고(실시간 시세가 한쪽에만 쌓인다) api의 증권사 경로도 둘 중 하나를 골라야 한다.
            if (market in KRX_MARKETS) {
                moved += jdbc.update(
                    "UPDATE stocks SET is_active = false WHERE symbol = ? AND market <> ? AND market IN ('KOSPI', 'KOSDAQ', 'KONEX') AND is_active",
                    s.symbol, market,
                )
            }
        }

        if (moved > 0) log.warn("Stock master sync: deactivated {} rows listed under another KRX market (market transfer)", moved)
        log.info("Stock master sync complete: upserted={}, total={}", upserted, stocks.size)
    }

    companion object {
        private val KRX_MARKETS = setOf("KOSPI", "KOSDAQ", "KONEX")
    }
}
