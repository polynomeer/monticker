package com.monticker.api.watchlist.api

import com.monticker.api.watchlist.application.WatchlistSearchResult
import com.monticker.api.watchlist.application.WatchlistService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/watchlists")
class WatchlistController(
    private val watchlistService: WatchlistService,
) {
    @GetMapping
    fun getGroups(@AuthenticationPrincipal userId: Long): ResponseEntity<List<WatchlistGroupResponse>> {
        val groups = watchlistService.getGroups(userId)
        return ResponseEntity.ok(groups.map { WatchlistGroupResponse.from(it) })
    }

    @PostMapping("/groups")
    fun createGroup(
        @AuthenticationPrincipal userId: Long,
        @RequestBody request: CreateGroupRequest,
    ): ResponseEntity<WatchlistGroupResponse> {
        if (request.name.isBlank()) return ResponseEntity.badRequest().build()
        val group = watchlistService.createGroup(userId, request.name)
        return ResponseEntity.ok(WatchlistGroupResponse.from(group))
    }

    @PostMapping("/groups/{groupId}/items")
    fun addItem(
        @PathVariable groupId: Long,
        @AuthenticationPrincipal userId: Long,
        @RequestBody request: AddItemRequest,
    ): ResponseEntity<WatchlistItemResponse> {
        return try {
            val item = watchlistService.addItem(userId, groupId, request.stockId, request.memo)
            ResponseEntity.ok(WatchlistItemResponse.from(item))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        } catch (e: IllegalStateException) {
            ResponseEntity.badRequest().build()
        }
    }

    @DeleteMapping("/items/{itemId}")
    fun removeItem(
        @PathVariable itemId: Long,
        @AuthenticationPrincipal userId: Long,
    ): ResponseEntity<Void> {
        return try {
            watchlistService.removeItem(userId, itemId)
            ResponseEntity.noContent().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * 내 관심종목 내 키워드 검색.
     * 종목명·심볼·섹터·메모를 통합 검색한다.
     *
     * GET /api/watchlists/search?query=반도체
     * GET /api/watchlists/search?query=삼성&limit=10
     */
    @GetMapping("/search")
    fun search(
        @AuthenticationPrincipal userId: Long,
        @RequestParam query: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<WatchlistSearchResponse>> {
        if (query.isBlank()) return ResponseEntity.badRequest().build()
        val results = watchlistService.search(userId, query, limit)
        return ResponseEntity.ok(results.map { WatchlistSearchResponse.from(it) })
    }
}

data class CreateGroupRequest(val name: String)
data class AddItemRequest(val stockId: Long, val memo: String? = null)

data class WatchlistSearchResponse(
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
        fun from(r: WatchlistSearchResult) = WatchlistSearchResponse(
            itemId      = r.itemId,
            groupId     = r.groupId,
            groupName   = r.groupName,
            stockId     = r.stockId,
            symbol      = r.symbol,
            stockName   = r.stockName,
            sector      = r.sector,
            memo        = r.memo,
            targetPrice = r.targetPrice,
            score       = r.score,
        )
    }
}
