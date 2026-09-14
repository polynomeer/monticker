package com.monticker.api.common.search

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.BulkRequest
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * ADR-042 — 유일한 ES writer. `search.index`를 배치로 받아 ES Bulk API 한 번에 쓴다.
 * @RetryableTopic은 배치 리스너를 지원하지 않는다 — 대신 [SearchIndexKafkaConfig]의 DefaultErrorHandler가
 * 배치 전체를 지수 백오프로 4회 재시도한 뒤 각 레코드를 `search.index-dlt`로 보낸다. 부분 실패(bulk 항목 일부
 * 오류)도 배치 전체를 재시도한다 — 색인은 멱등(같은 id 덮어쓰기)이라 중복 적용이 안전하다.
 *
 * 메트릭: search_index_documents_total{op}, search_index_failed_total{index}, search_index_lag_seconds
 * (Kafka 레코드 타임스탬프 = 외부화 시점 → 색인 완료). 알람: SearchIndexLag / DltMessagesGrowing.
 */
@Component
class SearchIndexConsumer(
    private val client: ElasticsearchClient,
    private val objectMapper: ObjectMapper,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val indexed = registry.counter("search_index_documents_total", "op", "index")
    private val deleted = registry.counter("search_index_documents_total", "op", "delete")
    private val lag: Timer = Timer.builder("search_index_lag_seconds")
        .description("이벤트 외부화(Kafka 타임스탬프) → ES 색인 완료")
        .publishPercentiles(0.5, 0.95, 0.99).register(registry)
    private val registry = registry
    private val dlt = registry.counter("dlt_messages_total", "topic", "search.index")   // 0으로 미리 등록 — 알람이 처음부터 검증 가능하게

    @KafkaListener(
        topics = ["search.index"], groupId = "monticker-search-indexer",
        batch = "true", containerFactory = "searchIndexContainerFactory",
    )
    fun onBatch(records: List<ConsumerRecord<String, String>>) {
        if (records.isEmpty()) return
        val events = records.map { objectMapper.readValue(it.value(), SearchIndexEvent::class.java) }
        val bulk = BulkRequest.Builder()
        events.forEach { e ->
            when (e.op) {
                SearchIndexEvent.Op.INDEX -> bulk.operations { op ->
                    // document(map): ES 자바 클라이언트의 JsonpMapper가 Map을 직렬화한다. withJson()은 문서가 아니라
                    // IndexOperation 전체를 JSON에서 읽는 API라 "cannot be read from JSON"으로 실패한다(라이브에서 겪음).
                    op.index<Any> { i -> i.index(e.index).id(e.docId).document(e.payload) }
                }
                SearchIndexEvent.Op.DELETE -> bulk.operations { op -> op.delete { d -> d.index(e.index).id(e.docId) } }
            }
        }
        val response = try {
            client.bulk(bulk.build())
        } catch (ex: Exception) {
            log.error("[SearchIndex] bulk {}건 실패 — 재시도/DLT로 넘긴다: {}", events.size, ex.toString())
            throw ex
        }
        if (response.errors()) {
            val failed = response.items().filter { it.error() != null }
            failed.groupBy { it.index() }.forEach { (idx, items) ->
                registry.counter("search_index_failed_total", "index", idx ?: "?").increment(items.size.toDouble())
            }
            val sample = failed.first()
            throw IllegalStateException("ES bulk 부분 실패 ${failed.size}/${events.size}: [${sample.index()}/${sample.id()}] ${sample.error()?.reason()}")
        }
        events.forEach { if (it.op == SearchIndexEvent.Op.INDEX) indexed.increment() else deleted.increment() }
        val now = Instant.now()
        records.forEach { lag.record(Duration.between(Instant.ofEpochMilli(it.timestamp()), now)) }
        log.debug("[SearchIndex] bulk {} docs in {}ms", events.size, response.took())
    }

    @KafkaListener(topics = ["search.index-dlt"], groupId = "monticker-search-indexer-dlt")
    fun onDlt(record: ConsumerRecord<String, String>) {
        dlt.increment()   // 알람: DltMessagesGrowing · SearchIndexDltGrowing
        log.error("[DLT] search.index 최종 실패 — 문서가 ES에 반영되지 않았다. key={} payload={}", record.key(), record.value().take(500))
    }
}
