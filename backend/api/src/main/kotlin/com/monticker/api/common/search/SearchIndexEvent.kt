package com.monticker.api.common.search

import org.springframework.modulith.events.Externalized

/**
 * ADR-042 — ES 인덱싱 아웃박스 이벤트. 도메인 모듈은 ES를 직접 쓰지 않고, DB 트랜잭션 안에서 이 이벤트를
 * 발행한다. Modulith가 커밋 후 Kafka `search.index`로 외부화하고(실패 시 event_publication에 남아 5분 뒤
 * 재전송), [SearchIndexConsumer]가 벌크로 색인한다. 키 = "{index}:{docId}" — 같은 문서의 색인·삭제 순서 보장.
 *
 * payload는 완성된 문서(도메인 모듈이 조인까지 끝낸 결과)다 — CDC를 쓰지 않는 이유(ADR-042 Reasons).
 */
@Externalized("search.index::#{#this.index + ':' + #this.docId}")
data class SearchIndexEvent(
    val index: String,
    val docId: String,
    val op: Op,
    val payload: Map<String, Any?>? = null,   // DELETE면 null
) {
    enum class Op { INDEX, DELETE }

    companion object {
        fun index(index: String, docId: String, payload: Map<String, Any?>) = SearchIndexEvent(index, docId, Op.INDEX, payload)
        fun delete(index: String, docId: String) = SearchIndexEvent(index, docId, Op.DELETE)
    }
}
