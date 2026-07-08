package com.monticker.api.batch.backfill

import org.springframework.batch.core.partition.support.Partitioner
import org.springframework.batch.item.ExecutionContext
import java.time.LocalDate

/**
 * 날짜 범위를 30일 청크로 분할한다.
 * 각 파티션은 partitionDate(YYYY-MM-DD) ExecutionContext 키를 가지며,
 * Reader는 해당 월의 데이터를 한 번에 읽는다.
 */
class DateRangePartitioner(
    private val stockId: Long,
    private val fromDate: LocalDate,
    private val toDate: LocalDate,
) : Partitioner {

    override fun partition(gridSize: Int): Map<String, ExecutionContext> {
        val partitions = mutableMapOf<String, ExecutionContext>()
        var current = fromDate
        var index = 0

        while (!current.isAfter(toDate)) {
            val chunkEnd = minOf(current.plusDays(29), toDate)
            val ctx = ExecutionContext()
            ctx.putLong("stockId", stockId)
            ctx.putString("fromDate", current.toString())
            ctx.putString("toDate", chunkEnd.toString())
            partitions["partition$index"] = ctx
            current = chunkEnd.plusDays(1)
            index++
        }

        return partitions
    }
}
