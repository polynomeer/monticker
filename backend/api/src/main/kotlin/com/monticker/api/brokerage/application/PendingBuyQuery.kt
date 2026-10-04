package com.monticker.api.brokerage.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * ADR-058 — 이 계좌의 "아직 증권사 보유 내역에 없을 수 있는 매수"를 종목별 수량으로 모은다. 리스크 게이트의 노출 계산에 더한다.
 *
 * | 상태 | 수량 |
 * |---|---|
 * | PENDING_SUBMIT·UNKNOWN (날짜 무관) | quantity — 증권사에 있을 수 있다 |
 * | SUBMITTED·PARTIALLY_FILLED (최근 [OPEN_ORDER_WINDOW] 접수) | quantity − filled_qty — 당일 유효 주문이지만, 장 마감 후 접수가
 *   다음 세션으로 넘어갈 수 있어(Toss 미검증) KST 자정이 아니라 24시간으로 본다 |
 * | FILLED·PARTIALLY_FILLED (체결 후 [FILL_REFLECT_WINDOW] 이내) | filled_qty — 잔고 API 반영 지연 동안 빠지지 않게 |
 *
 * 주문 준비(ADR-056 tx1)는 사용자별 advisory lock으로 직렬화되고 의도 행이 락 해제 전에 커밋되므로, 동시에 들어온 두 번째 주문은
 * 첫 번째를 여기서 본다. 방금 준비 중인 주문 자신은 아직 행이 없어 세지 않는다.
 */
@Component
class PendingBuyQuery(private val jdbc: JdbcTemplate) {

    fun pendingBuys(accountId: Long, now: Instant = Instant.now()): Map<Long, Int> {
        val openSince = Timestamp.from(now.minus(OPEN_ORDER_WINDOW))
        val fillCutoff = Timestamp.from(now.minus(FILL_REFLECT_WINDOW))
        return jdbc.query(
            """
            SELECT stock_id, SUM(
                CASE WHEN status IN ('PENDING_SUBMIT','UNKNOWN') THEN quantity
                     WHEN status IN ('SUBMITTED','PARTIALLY_FILLED') AND submitted_at >= ? THEN quantity - filled_qty
                     ELSE 0 END
              + CASE WHEN status IN ('FILLED','PARTIALLY_FILLED') AND filled_at > ? THEN filled_qty ELSE 0 END
            ) AS qty
            FROM brokerage_orders
            WHERE account_id = ? AND side = 'BUY' AND stock_id IS NOT NULL
              AND (status IN ('PENDING_SUBMIT','UNKNOWN') OR submitted_at >= ? OR filled_at > ?)
            GROUP BY stock_id
            """.trimIndent(),
            { rs, _ -> rs.getLong("stock_id") to rs.getInt("qty") },
            openSince, fillCutoff, accountId, openSince, fillCutoff,
        ).filter { it.second > 0 }.toMap()
    }

    companion object {
        /**
         * 미체결 주문을 대기로 보는 창. 두 증권사 주문은 당일 유효지만 장 마감 후 접수분이 다음 세션에 살아 있을 수 있다.
         * 체결·취소는 리스크 게이트 직전 같은 종목 조회(BrokerageService.refreshOpenBuys)로 걷어낸다.
         */
        val OPEN_ORDER_WINDOW: Duration = Duration.ofHours(24)
        /** 체결 후 증권사 잔고 API에 반영되기까지 기다려 주는 창. 이 동안은 보유와 대기에 이중으로 잡힐 수 있다(안전 쪽). */
        val FILL_REFLECT_WINDOW: Duration = Duration.ofMinutes(2)
    }
}
