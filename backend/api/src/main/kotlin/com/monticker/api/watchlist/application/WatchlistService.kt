package com.monticker.api.watchlist.application

import com.monticker.api.stock.application.StockService
import com.monticker.api.watchlist.domain.WatchlistGroup
import com.monticker.api.watchlist.domain.WatchlistItem
import com.monticker.api.watchlist.infrastructure.WatchlistGroupRepository
import com.monticker.api.watchlist.infrastructure.WatchlistItemDocument
import com.monticker.api.watchlist.infrastructure.WatchlistItemRepository
import com.monticker.api.watchlist.infrastructure.WatchlistSearchRepository
import org.slf4j.LoggerFactory
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
    private val watchlistSearchRepository: WatchlistSearchRepository,
    private val esOps: ElasticsearchOperations,
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

        indexToEs(saved, group, stock.name, stock.sector)
        return saved
    }

    fun removeItem(userId: Long, itemId: Long) {
        val item = itemRepository.findById(itemId).orElseThrow {
            NoSuchElementException("Watchlist item not found: $itemId")
        }
        require(item.group.userId == userId) { "Access denied" }
        itemRepository.delete(item)
        deleteFromEs(itemId)
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
            log.warn("ES watchlist search failed for userId={}: {}", userId, e.message)
            emptyList()
        }
    }

    // ── ES 동기화 ─────────────────────────────────────────────────────────────

    private fun indexToEs(item: WatchlistItem, group: WatchlistGroup, stockName: String, sector: String?) {
        try {
            watchlistSearchRepository.save(
                WatchlistItemDocument(
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
            )
        } catch (e: Exception) {
            log.warn("ES indexing failed for watchlist item id={}: {}", item.id, e.message)
        }
    }

    private fun deleteFromEs(itemId: Long) {
        try {
            watchlistSearchRepository.deleteById(itemId.toString())
        } catch (e: Exception) {
            log.warn("ES delete failed for watchlist item id={}: {}", itemId, e.message)
        }
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
