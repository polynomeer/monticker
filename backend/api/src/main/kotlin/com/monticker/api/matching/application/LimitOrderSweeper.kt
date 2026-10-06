package com.monticker.api.matching.application

import com.monticker.api.common.domain.CandleFreshness
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * ADR-074 — 미체결 모의 지정가 주문을 시세가 교차하는 순간 체결한다.
 *
 * 이전엔 사가가 미체결 LIMIT을 pod 힙의 호가창(MatchingOrderBookService)에 넣기만 하고 아무도 체결하지 않았다
 * (ADR-048 주석). 호가창은 pod마다 갈라져 있어 체결 근거가 될 수 없다 — 그래서 체결 판정은 DB의 `orders`와
 * 최신 `candles_1m` 종가로 한다. pod가 여럿이어도 [LimitOrderFiller]의 SKIP LOCKED가 한 번만 체결되게 한다.
 *
 * 후보 선정은 락 없이 넓게 읽고, 실제 판정·체결은 주문별 트랜잭션에서 다시 한다(읽은 뒤 바뀌었을 수 있다).
 */
@Component
class LimitOrderSweeper(
    private val jdbc: JdbcTemplate,
    private val filler: LimitOrderFiller,
    @Value("\${app.matching.limit-sweep.batch:200}") private val batch: Int = 200,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CANDIDATES_SQL = """
            SELECT o.id FROM orders o
            JOIN LATERAL (
                SELECT c.close FROM candles_1m c
                WHERE c.stock_id = o.stock_id AND c.candle_time >= now() - interval '${CandleFreshness.MAX_AGE_SQL}'
                ORDER BY c.candle_time DESC LIMIT 1
            ) p ON true
            WHERE o.order_type = 'LIMIT' AND o.status IN ('PENDING', 'PARTIALLY_FILLED')
              AND ((o.side = 'BUY' AND o.limit_price >= p.close) OR (o.side = 'SELL' AND o.limit_price <= p.close))
            ORDER BY o.created_at
            LIMIT ?
        """
    }

    @Scheduled(fixedDelayString = "\${app.matching.limit-sweep.interval-ms:3000}", initialDelay = 20_000)
    fun sweep(): Int {
        val ids = jdbc.query(CANDIDATES_SQL.trimIndent(), { rs, _ -> rs.getLong("id") }, batch)
        var filled = 0
        for (id in ids) {
            // 한 건의 실패가 나머지를 막지 않게 한다 — 실패한 주문은 미체결로 남고 다음 주기에 다시 시도된다.
            val outcome = runCatching { filler.fillIfCrossed(id) }
                .onFailure { log.warn("[LimitSweep] orderId={} fill failed: {}", id, it.message) }
                .getOrNull()
            if (outcome == LimitFillOutcome.FILLED) filled++
        }
        if (filled > 0) log.info("[LimitSweep] filled {}/{} crossed limit orders", filled, ids.size)
        return filled
    }
}
