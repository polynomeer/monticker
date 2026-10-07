package com.monticker.api.common.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class SettlementDateRealignmentTest {

    private val cal = V83Seed.calendar()
    private val today = LocalDate.of(2026, 9, 23)

    @Test
    fun `moves a holiday settle date computed by the old weekends-only rule, once`() {
        // 9/22(화) 10:00 KST 체결 → 옛 규칙 9/24(추석 연휴) → 새 규칙: 9/23 T+1, 9/28 T+2
        val rows = listOf(SettlementDateRealignment.Row(1, LocalDate.of(2026, 9, 24), Instant.parse("2026-09-22T01:00:00Z")))
        val moves = mutableListOf<Triple<Long, LocalDate, LocalDate>>()
        val store = mutableMapOf(1L to LocalDate.of(2026, 9, 24))
        val move = { id: Long, old: LocalDate, new: LocalDate ->
            if (store[id] == old) { store[id] = new; moves += Triple(id, old, new); 1 } else 0
        }

        assertThat(SettlementDateRealignment.realign(today, rows, cal, move)).isEqualTo(1)
        assertThat(store[1]).isEqualTo(LocalDate.of(2026, 9, 28))

        // 두 번째 실행(또는 다른 파드)이 옛 날짜로 읽은 행을 다시 옮기려 해도 조건부 UPDATE가 0건 → 멱등
        assertThat(SettlementDateRealignment.realign(today, rows, cal, move)).isEqualTo(0)
        // 새 값으로 다시 읽으면 기대값과 같아 건드리지 않는다
        val reread = listOf(SettlementDateRealignment.Row(1, store[1]!!, rows[0].basis))
        assertThat(SettlementDateRealignment.realign(today, reread, cal, move)).isEqualTo(0)
        assertThat(moves).hasSize(1)
    }

    @Test
    fun `does not touch past-due rows or pull a date before today`() {
        val calls = mutableListOf<Long>()
        val move = { id: Long, _: LocalDate, _: LocalDate -> calls += id; 1 }
        val rows = listOf(
            // 이미 지난 정산일(다음 배치가 처리)
            SettlementDateRealignment.Row(1, LocalDate.of(2026, 9, 22), Instant.parse("2026-09-17T01:00:00Z")),
            // 기대값(9/18)이 오늘(9/23)보다 앞 — 과거로 당기지 않는다
            SettlementDateRealignment.Row(2, LocalDate.of(2026, 9, 25), Instant.parse("2026-09-16T01:00:00Z")),
            // 이미 맞는 값
            SettlementDateRealignment.Row(3, LocalDate.of(2026, 9, 29), Instant.parse("2026-09-23T01:00:00Z")),
        )
        assertThat(SettlementDateRealignment.realign(today, rows, cal, move)).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `UTC-day basis error from the old code is corrected to the KST trade date`() {
        // 9/30(수) 08:00 KST = 9/29 23:00 UTC. 옛 코드가 UTC 서버에서 9/29를 체결일로 잡아 T+2 = 10/1로 계산.
        // KST 체결일 9/30 → 10/1 T+1, 10/2 T+2
        val rows = listOf(SettlementDateRealignment.Row(7, LocalDate.of(2026, 10, 1), Instant.parse("2026-09-29T23:00:00Z")))
        var moved: LocalDate? = null
        SettlementDateRealignment.realign(LocalDate.of(2026, 9, 30), rows, cal, { _, _, n -> moved = n; 1 })
        assertThat(moved).isEqualTo(LocalDate.of(2026, 10, 2))
    }
}
