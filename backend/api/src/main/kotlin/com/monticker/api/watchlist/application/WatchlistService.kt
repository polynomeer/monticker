package com.monticker.api.watchlist.application

import com.monticker.api.common.metrics.SearchMetrics
import com.monticker.api.common.search.SearchIndexEvent
import com.monticker.api.stock.application.StockService
import com.monticker.api.watchlist.domain.WatchlistGroup
import com.monticker.api.watchlist.domain.WatchlistItem
import com.monticker.api.watchlist.infrastructure.WatchlistGroupRepository
import com.monticker.api.watchlist.infrastructure.WatchlistItemDocument
import com.monticker.api.watchlist.infrastructure.WatchlistItemRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class WatchlistService(
    private val groupRepository: WatchlistGroupRepository,
    private val itemRepository: WatchlistItemRepository,
    private val stockService: StockService,
    private val esOps: ElasticsearchOperations,
    private val searchMetrics: SearchMetrics,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getGroups(userId: Long): List<WatchlistGroup> =
        groupRepository.findAllByUserIdOrderBySortOrder(userId)

    fun createGroup(userId: Long, name: String): WatchlistGroup {
        require(name.isNotBlank()) { "Group name must not be blank" }
        val group = WatchlistGroup(userId = userId, name = name)
        return groupRepository.save(group)
    }

    fun addItem(userId: Long, groupId: Long, stockId: Long, memo: String?): WatchlistItem {
        val group = groupRepository.findById(groupId).orElseThrow {
            NoSuchElementException("Watchlist group not found: $groupId")
        }
        require(group.userId == userId) { "Access denied" }

        val stock = stockService.getById(stockId)

        check(!itemRepository.existsByGroupIdAndStockId(groupId, stockId)) {
            "Stock already in watchlist"
        }

        val item = WatchlistItem(group = group, stock = stock, memo = memo)
        val saved = itemRepository.save(item)

        publishIndex(saved, group, stock.name, stock.sector)
        return saved
    }

    fun removeItem(userId: Long, itemId: Long) {
        val item = itemRepository.findById(itemId).orElseThrow {
            NoSuchElementException("Watchlist item not found: $itemId")
        }
        require(item.group.userId == userId) { "Access denied" }
        itemRepository.delete(item)
        // ADR-042: 삭제도 이벤트 — 트랜잭션이 롤백되면 이벤트도 함께 사라진다
        events.publishEvent(SearchIndexEvent.delete(WatchlistIndexer.INDEX, itemId.toString()))
    }

    /**
     * 내 관심종목 중 키워드 검색 (ES). 종목명·심볼·섹터·메모를 통합 검색한다.
     */
    @Transactional(readOnly = true)
    fun search(userId: Long, query: String, limit: Int = 20): List<WatchlistSearchResult> {
        return try {
            val nativeQuery = NativeQuery.builder()
                .withQuery { q ->
                    q.bool { b ->
                        b.filter { f -> f.term { t -> t.field("userId").value(userId) } }
                        b.must { m ->
                            m.bool { mb ->
                                mb.should { s ->
                                    s.multiMatch { mm ->
                                        mm.query(query)
                                            .fields("stockName^3", "symbol^3", "sector^1", "memo^2")
                                            .analyzer("nori_analyzer")
                                            .fuzziness("AUTO")
                                    }
                                }
                                // symbol 완전 일치 최우선
                                mb.should { s ->
                                    s.term { t -> t.field("symbol").value(query.uppercase()).boost(5.0f) }
                                }
                                mb
                            }
                        }
                        b
                    }
                }
                .withMaxResults(limit.coerceIn(1, 50))
                .build()

            esOps.search(nativeQuery, WatchlistItemDocument::class.java)
                .map { hit -> WatchlistSearchResult.from(hit.content, hit.score) }
                .toList()
        } catch (e: Exception) {
            searchMetrics.fallback("watchlist_items")
            log.warn("ES watchlist search failed for userId={}: {}", userId, e.message)
            emptyList()
        }
    }

    // ── ES 동기화 (ADR-042) ────────────────────────────────────────────────────
    // 이전엔 여기서 ES를 직접 썼다 — 클래스 레벨 @Transactional 안에서 커밋 전에 색인해 롤백되면 유령 문서가
    // 남았고, 실패는 WARN으로 삼켜졌다. 이제 완성된 문서를 SearchIndexEvent에 실어 같은 트랜잭션에 기록한다
    // (event_publication). 커밋 후 Kafka search.index → SearchIndexConsumer가 벌크 색인하고, 실패는 재시도·DLT·
    // Outbox 재전송이 받는다. 이 서비스는 더 이상 ES writer가 아니다 — 검색(read)만 한다.

    private fun publishIndex(item: WatchlistItem, group: WatchlistGroup, stockName: String, sector: String?) {
        val doc = WatchlistItemDocument(
            id          = item.id.toString(),
            userId      = group.userId,
            groupId     = group.id,
            groupName   = group.name,
            stockId     = item.stock.id,
            symbol      = item.stock.symbol,
            stockName   = stockName,
            sector      = sector,
            memo        = item.memo,
            targetPrice = item.targetPrice?.toDouble(),
            createdAt   = item.createdAt,
        )
        // Spring Data ES 컨버터로 직렬화해야 @Field(format = epoch_millis) 같은 매핑 규칙과 _id 제외가 그대로 적용된다
        val payload: Map<String, Any?> = esOps.elasticsearchConverter.mapObject(doc)
        events.publishEvent(SearchIndexEvent.index(WatchlistIndexer.INDEX, doc.id, payload))
    }
}

data class WatchlistSearchResult(
    val itemId: Long,
    val groupId: Long,
    val groupName: String,
    val stockId: Long,
    val symbol: String,
    val stockName: String,
    val sector: String?,
    val memo: String?,
    val targetPrice: Double?,
    val score: Float?,
) {
    companion object {
        fun from(doc: WatchlistItemDocument, score: Float) = WatchlistSearchResult(
            itemId      = doc.id.toLong(),
            groupId     = doc.groupId,
            groupName   = doc.groupName,
            stockId     = doc.stockId,
            symbol      = doc.symbol,
            stockName   = doc.stockName,
            sector      = doc.sector,
            memo        = doc.memo,
            targetPrice = doc.targetPrice,
            score       = score,
        )
    }
}
