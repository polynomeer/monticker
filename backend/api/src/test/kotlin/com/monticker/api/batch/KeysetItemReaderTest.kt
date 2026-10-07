package com.monticker.api.batch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.item.ExecutionContext

/**
 * 2026-10 설계 리뷰 10a — 처리하면 조회 조건에서 빠지는 대상을 offset으로 페이징하면 청크 커밋마다
 * 결과 집합이 줄어 다음 페이지가 건너뛴다. 키셋 리더는 같은 상황에서 전부 읽어야 한다.
 */
class KeysetItemReaderTest {

    /** PENDING 정산 테이블 흉내 — 처리된 건은 조건(PENDING)에서 빠진다. */
    private class ShrinkingTable(ids: IntRange) {
        val pending = ids.map { it.toLong() }.toSortedSet()
        var fetches = 0
        fun fetch(afterId: Long, limit: Int): List<Long> {
            fetches++
            return pending.filter { it > afterId }.take(limit)
        }
    }

    private fun reader(table: ShrinkingTable, pageSize: Int = 50) =
        KeysetItemReader<Long>("test", pageSize, { it }) { afterId, limit -> table.fetch(afterId, limit) }

    /** 청크 지향 스텝처럼 pageSize 만큼 읽고, "커밋"(처리 → 조건에서 제거)한 뒤 다음 청크를 읽는다. */
    private fun runChunks(reader: KeysetItemReader<Long>, table: ShrinkingTable, chunk: Int, fail: Set<Long> = emptySet()): List<Long> {
        val read = mutableListOf<Long>()
        reader.open(ExecutionContext())
        while (true) {
            val items = generateSequence { reader.read() }.take(chunk).toList()
            if (items.isEmpty()) break
            read += items
            table.pending.removeAll(items.toSet() - fail)
        }
        return read
    }

    @Test
    fun `처리될 때마다 결과 집합이 줄어도 한 실행에서 전부 읽는다`() {
        val table = ShrinkingTable(1..120)

        val read = runChunks(reader(table), table, chunk = 50)

        assertThat(read).containsExactlyElementsOf((1L..120L).toList())
        assertThat(table.pending).isEmpty()
    }

    @Test
    fun `실패해 조건에 남은 건은 같은 실행에서 다시 읽지 않는다`() {
        val table = ShrinkingTable(1..60)

        val read = runChunks(reader(table, pageSize = 10), table, chunk = 10, fail = setOf(5L, 37L))

        assertThat(read).containsExactlyElementsOf((1L..60L).toList())
        assertThat(table.pending).containsExactly(5L, 37L)
    }

    @Test
    fun `마지막 페이지가 꽉 차지 않으면 더 조회하지 않는다`() {
        val table = ShrinkingTable(1..7)

        runChunks(reader(table, pageSize = 5), table, chunk = 5)

        assertThat(table.fetches).isEqualTo(2)
    }

    @Test
    fun `재시작하면 저장된 마지막 id 다음부터 읽는다`() {
        val table = ShrinkingTable(1..10)
        val ctx = ExecutionContext()
        reader(table, pageSize = 4).apply {
            open(ctx)
            repeat(4) { read() }
            update(ctx)
        }

        val resumed = reader(table, pageSize = 4).apply { open(ctx) }
        val rest = generateSequence { resumed.read() }.toList()

        assertThat(rest).containsExactly(5L, 6L, 7L, 8L, 9L, 10L)
    }
}
