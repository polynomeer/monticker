package com.monticker.api.common.calendar

import java.time.Instant
import java.time.LocalDate

/**
 * ADR-086 — 이미 잡힌 PENDING 정산일을 캘린더 기준으로 다시 맞출 때의 공통 규칙(paper·brokerage가 같이 쓴다).
 *
 * - 대상은 정산일이 오늘 이후(오늘 포함)인 PENDING 행만. 이미 지난 PENDING은 다음 정산 배치가 처리한다.
 * - 새 정산일 = 기준 시각의 T+2. 새 날짜가 오늘보다 앞이면 옮기지 않는다(과거로 당기지 않는다).
 * - [move]는 "읽은 정산일 그대로이고 아직 PENDING"일 때만 바꾸고 바뀐 행 수를 돌려줘야 한다 — 멱등, 다중 파드 안전.
 */
object SettlementDateRealignment {

    data class Row(val id: Long, val settleDate: LocalDate, val basis: Instant)

    /** 옮긴 건수 */
    fun realign(
        today: LocalDate,
        rows: List<Row>,
        calendar: TradingCalendar,
        move: (id: Long, oldDate: LocalDate, newDate: LocalDate) -> Int,
        onMoved: (Row, LocalDate) -> Unit = { _, _ -> },
    ): Int {
        var moved = 0
        for (row in rows) {
            if (row.settleDate.isBefore(today)) continue
            val expected = calendar.settlementDate(row.basis)
            if (expected == row.settleDate || expected.isBefore(today)) continue
            if (move(row.id, row.settleDate, expected) == 1) {
                moved++
                onMoved(row, expected)
            }
        }
        return moved
    }
}
