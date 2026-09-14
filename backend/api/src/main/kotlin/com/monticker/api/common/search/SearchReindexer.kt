package com.monticker.api.common.search

/**
 * ADR-042 §5 — 인덱스별 전량 재색인. 이전엔 각 *Indexer가 @PostConstruct로 기동마다 돌았다 — 인덱스가 커지면
 * 기동 자체가 장애다. 이제 [SearchReindexRunner]가 `app.search.reindex-on-startup`(기본 false)일 때만 돌리고,
 * 운영에서는 관리자 엔드포인트(`POST /api/admin/search/reindex/{index}`)로 명시적으로 실행한다.
 * 실시간 반영은 [SearchIndexEvent] → [SearchIndexConsumer] 경로가 맡는다.
 */
interface SearchReindexer {
    val index: String
    val documentClass: Class<*>
    /** DB → ES 전량(또는 최근 N건) 동기화. 색인한 문서 수를 돌려준다. */
    fun reindexAll(): Int
}
