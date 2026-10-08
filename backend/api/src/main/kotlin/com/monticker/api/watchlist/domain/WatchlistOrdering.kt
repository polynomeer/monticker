package com.monticker.api.watchlist.domain

/** 그룹 안 종목 순서 계산 — 순수 함수(DB 없이 단위 테스트). 순서는 리스트 인덱스(0부터, 빈칸 없음)다. */
object WatchlistOrdering {

    /**
     * [ordered]에서 [itemId]를 [targetIndex] 자리로 옮긴 새 순서. 범위를 넘는 인덱스는 맨 끝으로 붙인다
     * (다른 탭에서 종목을 지워 목록이 짧아졌을 수 있다). [itemId]가 없으면 NoSuchElementException.
     */
    fun move(ordered: List<Long>, itemId: Long, targetIndex: Int): List<Long> {
        require(targetIndex >= 0) { "sortOrder는 0 이상이어야 합니다" }
        val from = ordered.indexOf(itemId)
        if (from < 0) throw NoSuchElementException("Watchlist item not found: $itemId")
        val rest = ordered.toMutableList().apply { removeAt(from) }
        rest.add(targetIndex.coerceAtMost(rest.size), itemId)
        return rest
    }
}
