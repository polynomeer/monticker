package com.monticker.api.stock.application

import com.monticker.api.stock.infrastructure.StockDocument
import com.monticker.api.stock.infrastructure.StockRepository
import org.slf4j.LoggerFactory
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Service

@Service
class StockSearchService(
    private val esOps: ElasticsearchOperations,
    private val stockRepository: StockRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * ES multi_match 검색:
     * 1. symbol keyword 완전/접두 일치 (boost 3)
     * 2. name autocomplete edge_ngram (boost 2)
     * 3. name nori 형태소 (boost 1)
     * 4. sector / industry nori (boost 0.5)
     *
     * ES 불가 시 PostgreSQL LIKE 검색으로 폴백.
     */
    fun search(query: String): List<StockSearchResult> {
        return try {
            searchFromEs(query)
        } catch (e: Exception) {
            log.warn("ES search failed, falling back to DB: {}", e.message)
            searchFromDb(query)
        }
    }

    private fun searchFromEs(query: String): List<StockSearchResult> {
        val nativeQuery = NativeQuery.builder()
            .withQuery { q ->
                q.bool { b ->
                    b.should { s ->
                        s.multiMatch { m ->
                            m.query(query)
                                .fields(
                                    "symbol^3",
                                    "name^2",
                                    "sector^0.5",
                                    "industry^0.5",
                                )
                                .fuzziness("AUTO")
                        }
                    }
                    // symbol 완전 일치는 최상위 boost
                    b.should { s ->
                        s.term { t ->
                            t.field("symbol").value(query.uppercase()).boost(5.0f)
                        }
                    }
                    // isActive 필터
                    b.filter { f ->
                        f.term { t -> t.field("isActive").value(true) }
                    }
                }
            }
            .withMaxResults(20)
            .build()

        val hits = esOps.search(nativeQuery, StockDocument::class.java)
        return hits.map { hit ->
            val doc = hit.content
            StockSearchResult(
                id       = doc.id.toLong(),
                symbol   = doc.symbol,
                name     = doc.name,
                market   = doc.market,
                sector   = doc.sector,
                score    = hit.score,
            )
        }.toList()
    }

    private fun searchFromDb(query: String): List<StockSearchResult> =
        stockRepository.searchByNameOrSymbol(query).map { stock ->
            StockSearchResult(
                id     = stock.id,
                symbol = stock.symbol,
                name   = stock.name,
                market = stock.market.name,
                sector = stock.sector,
                score  = null,
            )
        }
}

data class StockSearchResult(
    val id: Long,
    val symbol: String,
    val name: String,
    val market: String,
    val sector: String?,
    val score: Float?,
)
