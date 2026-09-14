package com.monticker.worker.search

import org.springframework.modulith.events.Externalized

/**
 * ADR-042 — ES 인덱싱 아웃박스 이벤트. backend/api의 `common.search.SearchIndexEvent`와 **JSON 형태가 같아야 한다**
 * (index, docId, op, payload) — 소비자는 api의 `SearchIndexConsumer` 하나다. worker는 ES를 직접 쓰지 않는다.
 *
 * 반드시 **DB 트랜잭션 안에서** 발행해야 한다 — Modulith는 트랜잭션 커밋 후 외부화하므로, 트랜잭션 밖에서
 * 발행하면 event_publication에 기록되지도 외부화되지도 않는다(TransactionalEventListener 의미론).
 * payload의 날짜는 epoch millis(Long) — api 문서 매핑이 `format = epoch_millis`다.
 */
@Externalized("search.index::#{#this.index + ':' + #this.docId}")
data class SearchIndexEvent(
    val index: String,
    val docId: String,
    val op: Op,
    val payload: Map<String, Any?>? = null,
) {
    enum class Op { INDEX, DELETE }

    companion object {
        fun index(index: String, docId: String, payload: Map<String, Any?>) = SearchIndexEvent(index, docId, Op.INDEX, payload)
    }
}
