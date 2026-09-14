package com.monticker.api.common.search

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.event.EventListener
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-042 — 인덱스의 소유자. 기동 시 모든 @Document 클래스를 찾아:
 * - 인덱스가 없으면 @Setting/@Field 로 만든다 (nori 분석기, 날짜 타입 …).
 * - 있으면 애노테이션이 기대하는 매핑과 실제 매핑을 필드 타입 단위로 대조한다. 어긋나면
 *   `search_index_mapping_mismatch{index}` 게이지를 1로 올리고 ERROR를 남긴다 — **자동으로 고치지 않는다.**
 *   ES는 기존 필드 타입을 바꿀 수 없어 재색인(관리자 엔드포인트)이 필요하다.
 *
 * 왜 필요한가: 모든 @Document가 createIndex=false였고 아무도 indexOps.create()를 부르지 않아, 인덱스는
 * 첫 save()가 만든 **동적 매핑**으로 생겼다(CH-04). `eventTime`이 text, `symbol`이 text — 날짜 정렬이 실패하고
 * nori·edge_ngram이 하나도 붙지 않은 채 몇 달을 돌았다. 설계(docs/elasticsearch.md)가 적용된 적이 없었다.
 */
@Component
class SearchIndexManager(
    private val esOps: ElasticsearchOperations,
    registry: MeterRegistry,
    @Value("\${app.search.manage-indices:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val registry = registry
    private val mismatch = ConcurrentHashMap<String, AtomicInteger>()

    data class IndexStatus(val index: String, val exists: Boolean, val created: Boolean, val mismatches: List<String>)

    fun documentClasses(): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply { addIncludeFilter(AnnotationTypeFilter(Document::class.java)) }
        return scanner.findCandidateComponents("com.monticker.api").map { Class.forName(it.beanClassName) }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!enabled) return
        // ES가 죽어 있어도 앱은 떠야 한다 (CH-04: 검색은 DB 폴백). 관측만 남긴다.
        runCatching { ensureAll() }
            .onFailure { log.warn("[SearchIndex] 인덱스 점검 실패 — ES 연결 불가? {}", it.message) }
    }

    fun ensureAll(): List<IndexStatus> = documentClasses().map { ensure(it) }

    fun ensure(cls: Class<*>): IndexStatus {
        val ops = esOps.indexOps(cls)
        val name = ops.indexCoordinates.indexName
        if (!ops.exists()) {
            ops.createWithMapping()
            log.info("[SearchIndex] 인덱스 생성: {} ({})", name, cls.simpleName)
            gauge(name).set(0)
            return IndexStatus(name, exists = false, created = true, mismatches = emptyList())
        }
        val diffs = compare(expected = ops.createMapping(), actual = ops.getMapping())
        gauge(name).set(if (diffs.isEmpty()) 0 else 1)
        if (diffs.isNotEmpty()) {
            log.error("[SearchIndex] 매핑 불일치 {} — 설계와 실제가 다르다. 재색인 필요 (POST /api/admin/search/reindex/{}): {}", name, name, diffs)
        }
        return IndexStatus(name, exists = true, created = false, mismatches = diffs)
    }

    /** 인덱스를 지우고 설계 매핑으로 다시 만든다 — 관리자 재색인의 1단계. 호출자가 문서를 다시 채운다. */
    fun recreate(cls: Class<*>): String {
        val ops = esOps.indexOps(cls)
        if (ops.exists()) ops.delete()
        ops.createWithMapping()
        gauge(ops.indexCoordinates.indexName).set(0)
        log.warn("[SearchIndex] 인덱스 재생성: {}", ops.indexCoordinates.indexName)
        return ops.indexCoordinates.indexName
    }

    /**
     * 기대 매핑의 각 property에 대해 실제 매핑의 type·analyzer가 같은지 본다. 실제에만 있는 필드(동적 매핑 잔재)는
     * 무시한다 — 설계가 요구하는 것이 만족되는지만 묻는다.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun compare(expected: Map<String, Any>, actual: Map<String, Any>): List<String> {
        val exp = expected["properties"] as? Map<String, Map<String, Any>> ?: return emptyList()
        val act = actual["properties"] as? Map<String, Map<String, Any>> ?: return exp.keys.map { "$it: 실제 매핑에 없음" }
        return exp.flatMap { (field, def) ->
            val a = act[field]
            when {
                a == null -> listOf("$field: 실제 매핑에 없음 (기대 ${def["type"]})")
                def["type"] != null && def["type"] != a["type"] -> listOf("$field: type 기대 ${def["type"]} / 실제 ${a["type"]}")
                def["analyzer"] != null && def["analyzer"] != a["analyzer"] -> listOf("$field: analyzer 기대 ${def["analyzer"]} / 실제 ${a["analyzer"]}")
                else -> emptyList()
            }
        }
    }

    private fun gauge(index: String): AtomicInteger =
        mismatch.computeIfAbsent(index) { name ->
            AtomicInteger(0).also { registry.gauge("search_index_mapping_mismatch", listOf(io.micrometer.core.instrument.Tag.of("index", name)), it) }
        }
}
