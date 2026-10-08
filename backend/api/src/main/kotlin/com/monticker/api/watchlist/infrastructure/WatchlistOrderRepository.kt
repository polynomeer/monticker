package com.monticker.api.watchlist.infrastructure

import com.monticker.api.watchlist.domain.WatchlistOrdering
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 관심종목 그룹 안 순서(watchlist_items.sort_order) 변경.
 *
 * 순서를 바꾸는 모든 쓰기(이동·추가·삭제)는 먼저 **그룹 행을 `FOR UPDATE`로 잠근다**. 같은 그룹을 건드리는 요청은
 * 여기서 한 줄로 서고, 잠금을 얻은 뒤의 다음 문장은 READ COMMITTED의 새 스냅샷으로 앞 요청의 커밋 결과를 본다.
 * 잠금과 읽기를 한 문장(CTE)에 넣으면 문장 시작 시점 스냅샷으로 읽어 앞 요청의 결과를 덮어쓰므로 일부러 나눴다.
 * 순서는 항상 0..n-1로 다시 매긴다 — 중복·빈칸이 남지 않는다.
 *
 * 모든 메서드는 호출부 트랜잭션 안에서 불러야 한다(잠금이 트랜잭션 끝까지 유지된다).
 * 소유자가 아니면 없는 것과 똑같이 null을 돌려준다(security-review H6 — 남의 id 존재 여부를 드러내지 않는다).
 */
@Repository
class WatchlistOrderRepository(private val jdbc: JdbcTemplate) {

    /** [userId]의 그룹이면 잠그고 true. 없거나 남의 그룹이면 false. */
    fun lockGroup(userId: Long, groupId: Long): Boolean =
        jdbc.queryForList(
            "SELECT id FROM watchlist_groups WHERE id = ? AND user_id = ? FOR UPDATE",
            Long::class.java, groupId, userId,
        ).isNotEmpty()

    /** [itemId]가 [userId]의 항목이면 그 그룹을 잠그고 그룹 id. 없거나 남의 항목이면 null. */
    fun lockGroupOfItem(userId: Long, itemId: Long): Long? =
        jdbc.queryForList(
            """
            SELECT g.id FROM watchlist_groups g
            JOIN watchlist_items i ON i.group_id = g.id
            WHERE i.id = ? AND g.user_id = ?
            FOR UPDATE OF g
            """.trimIndent(),
            Long::class.java, itemId, userId,
        ).firstOrNull()

    /** 그룹의 항목 id를 현재 순서대로. 같은 sort_order(예전 데이터)는 추가 순(id)으로 푼다. */
    fun itemIdsInOrder(groupId: Long): List<Long> =
        jdbc.queryForList(
            "SELECT id FROM watchlist_items WHERE group_id = ? ORDER BY sort_order, id",
            Long::class.java, groupId,
        )

    /** [ordered] 순서대로 sort_order를 0..n-1로 매긴다(문장 하나). 값이 이미 같은 행은 건드리지 않는다. */
    fun applyOrder(groupId: Long, ordered: List<Long>) {
        if (ordered.isEmpty()) return
        jdbc.update { con ->
            con.prepareStatement(
                """
                UPDATE watchlist_items w SET sort_order = v.ord - 1
                FROM unnest(?::bigint[]) WITH ORDINALITY AS v(id, ord)
                WHERE w.id = v.id AND w.group_id = ? AND w.sort_order <> v.ord - 1
                """.trimIndent(),
            ).apply {
                setArray(1, con.createArrayOf("bigint", ordered.toTypedArray()))
                setLong(2, groupId)
            }
        }
    }

    /**
     * [itemId]를 그룹 안 [targetIndex](0부터) 자리로 옮기고 실제로 놓인 자리를 돌려준다(범위를 넘으면 맨 끝).
     * 없는 항목·남의 항목이면 NoSuchElementException(같은 404).
     */
    fun moveItem(userId: Long, itemId: Long, targetIndex: Int): Int {
        require(targetIndex >= 0) { "sortOrder는 0 이상이어야 합니다" }
        val groupId = lockGroupOfItem(userId, itemId)
            ?: throw NoSuchElementException("Watchlist item not found: $itemId")
        val next = WatchlistOrdering.move(itemIdsInOrder(groupId), itemId, targetIndex)
        applyOrder(groupId, next)
        return next.indexOf(itemId)
    }

    /** 새 항목이 붙을 자리(맨 끝). 그룹을 잠근 뒤 불러야 동시 추가가 같은 값을 받지 않는다. */
    fun nextSortOrder(groupId: Long): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(sort_order) + 1, 0) FROM watchlist_items WHERE group_id = ?",
            Int::class.java, groupId,
        ) ?: 0

    /** 삭제 뒤 남은 항목을 0..n-1로 다시 매긴다(그룹을 잠근 뒤). */
    fun compact(groupId: Long) = applyOrder(groupId, itemIdsInOrder(groupId))
}
