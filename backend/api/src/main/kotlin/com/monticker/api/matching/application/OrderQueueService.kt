package com.monticker.api.matching.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

/**
 * 대기열 한 조각 — 같은 가격에서 접수 순으로 이어진 주문 묶음.
 *
 * 남의 주문은 이어진 구간을 하나로 합쳐 건수·잔량 합만 준다(id·사용자·접수 시각·개별 수량 목록 없음).
 * 내 주문은 한 건이 한 조각이고 [orderId]가 있다.
 */
data class QueueSlice(
    val mine: Boolean,
    val orderCount: Int,
    val quantity: Long,
    /** 내 주문일 때만. 남의 조각은 null. */
    val orderId: Long?,
    /** 이 조각의 첫 주문 순번(1부터, 같은 가격·방향 안). */
    val startPosition: Int,
)

data class QueueLevel(
    val price: BigDecimal,
    val orderCount: Int,
    val quantity: Long,
    /** 접수 순(먼저 체결 처리되는 쪽이 앞). */
    val slices: List<QueueSlice>,
)

/** 내 미체결 지정가 한 건의 대기 위치. */
data class MyQueuePosition(
    val orderId: Long,
    val side: String,
    val price: BigDecimal,
    /** 같은 가격·방향에서 접수 순 순번(1부터). */
    val position: Int,
    /** 내 앞의 주문 수와 잔량 합(내 다른 주문 포함). */
    val aheadCount: Int,
    val aheadQuantity: Long,
    val remainingQuantity: Long,
    /** 이 가격 전체 대기 건수. */
    val levelOrderCount: Int,
)

data class OrderQueueSnapshot(
    val stockId: Long,
    val asOf: Instant,
    /** 높은 가격부터(최우선 매수가 먼저). */
    val bids: List<QueueLevel>,
    /** 낮은 가격부터(최우선 매도가 먼저). */
    val asks: List<QueueLevel>,
    /** 내 미체결 지정가 전부 — 표시 상한([OrderQueueService.MAX_LEVELS]) 밖 가격도 포함. */
    val mine: List<MyQueuePosition>,
)

/** SQL 한 행 = 대기열 조각 하나. */
data class QueueSliceRow(
    val side: String,
    val price: BigDecimal,
    val mine: Boolean,
    val orderId: Long?,
    val startPosition: Int,
    val orderCount: Int,
    val quantity: Long,
)

/**
 * ADR-096 — 모의 지정가 대기열(가격별 조각)과 내 주문의 대기 순번. 읽기 전용.
 *
 * 순번 = 스위퍼([LimitOrderSweeper])가 실제로 처리하는 순서 `(created_at, id)`의 같은 가격·방향 안 위치다.
 * 모의 체결은 유동성 제한이 없어 교차하면 순번과 관계없이 모두 같은 종가로 전량 체결된다 — 순번은 "처리 순서"이지
 * "체결 여부"가 아니다. 또 제출 순간 교차한 새 지정가는 사가가 즉시 체결하므로 같은 가격의 앞선 주문보다 먼저(최대
 * 스위퍼 한 주기) 체결될 수 있다. 엄격한 가격-시간 우선 FIFO가 아니라는 것을 화면이 그대로 말한다.
 *
 * 일관성: 순번 계산·조각 합치기·집계를 SQL 한 문장으로 한다 — READ COMMITTED에서도 한 문장은 한 스냅샷을 본다.
 * 남의 개별 주문 행은 DB 밖으로 나오지 않는다.
 */
@Service
class OrderQueueService(private val jdbc: JdbcTemplate) {

    companion object {
        /** 방향마다 최우선부터 보여 줄 가격 수. 내 주문이 걸린 가격은 이 밖이어도 포함한다. */
        const val MAX_LEVELS = 20

        // 1) q: 미체결 지정가에 같은 가격·방향 안 순번을 매긴다(스위퍼와 같은 created_at, id).
        // 2) r: 내 주문 여부로 연속 구간(gaps-and-islands)을 나눈다 — 남의 주문이 이어진 구간은 grp가 같다.
        // 3) 남의 구간은 합치고, 내 주문은 한 건씩 둔다.
        const val QUEUE_SQL = """
            WITH q AS (
                SELECT id, user_id, side, limit_price, quantity - filled_qty AS rem,
                       row_number() OVER (PARTITION BY side, limit_price ORDER BY created_at, id) AS pos
                FROM orders
                WHERE stock_id = ? AND order_type = 'LIMIT' AND status IN ('PENDING', 'PARTIALLY_FILLED')
                  AND limit_price IS NOT NULL AND quantity > filled_qty
            ), r AS (
                SELECT q.*, (q.user_id = ?) AS mine,
                       q.pos - row_number() OVER (PARTITION BY q.side, q.limit_price, (q.user_id = ?) ORDER BY q.pos) AS grp
                FROM q
            )
            SELECT side, limit_price, mine,
                   MAX(CASE WHEN mine THEN id END) AS my_order_id,
                   MIN(pos) AS start_pos, COUNT(*) AS cnt, SUM(rem) AS qty
            FROM r
            GROUP BY side, limit_price, mine, CASE WHEN mine THEN id ELSE grp END
            ORDER BY side, limit_price, start_pos
        """

        /** SQL 조각 행 → 화면용 스냅샷. 순수 함수라 단위 테스트로 순번·상한을 고정한다. */
        fun assemble(stockId: Long, rows: List<QueueSliceRow>, asOf: Instant, maxLevels: Int = MAX_LEVELS): OrderQueueSnapshot {
            val levelsBySide = rows.groupBy { it.side }.mapValues { (_, sideRows) ->
                sideRows.groupBy { it.price }.map { (price, slices) ->
                    val ordered = slices.sortedBy { it.startPosition }
                    QueueLevel(
                        price = price,
                        orderCount = ordered.sumOf { it.orderCount },
                        quantity = ordered.sumOf { it.quantity },
                        slices = ordered.map { QueueSlice(it.mine, it.orderCount, it.quantity, if (it.mine) it.orderId else null, it.startPosition) },
                    )
                }
            }

            val mine = levelsBySide.flatMap { (side, levels) ->
                levels.flatMap { level ->
                    var aheadCount = 0
                    var aheadQty = 0L
                    level.slices.mapNotNull { s ->
                        val pos = if (s.mine && s.orderId != null) MyQueuePosition(
                            orderId = s.orderId, side = side, price = level.price, position = s.startPosition,
                            aheadCount = aheadCount, aheadQuantity = aheadQty, remainingQuantity = s.quantity,
                            levelOrderCount = level.orderCount,
                        ) else null
                        aheadCount += s.orderCount
                        aheadQty += s.quantity
                        pos
                    }
                }
            }.sortedWith(compareBy({ it.side }, { it.price }, { it.position }))

            fun cap(levels: List<QueueLevel>): List<QueueLevel> {
                val top = levels.take(maxLevels)
                val extra = levels.drop(maxLevels).filter { l -> l.slices.any { it.mine } }
                return top + extra
            }
            return OrderQueueSnapshot(
                stockId = stockId,
                asOf = asOf,
                bids = cap(levelsBySide["BUY"].orEmpty().sortedByDescending { it.price }),
                asks = cap(levelsBySide["SELL"].orEmpty().sortedBy { it.price }),
                mine = mine,
            )
        }
    }

    fun snapshot(userId: Long, stockId: Long): OrderQueueSnapshot {
        val rows = jdbc.query(QUEUE_SQL.trimIndent(), { rs, _ ->
            val mine = rs.getBoolean("mine")
            QueueSliceRow(
                side = rs.getString("side"),
                price = rs.getBigDecimal("limit_price").stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it },
                mine = mine,
                orderId = rs.getLong("my_order_id").takeIf { mine && !rs.wasNull() },
                startPosition = rs.getInt("start_pos"),
                orderCount = rs.getInt("cnt"),
                quantity = rs.getLong("qty"),
            )
        }, stockId, userId, userId)
        return assemble(stockId, rows, Instant.now())
    }
}
