package com.monticker.api.watchlist.api

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
}

data class CreateGroupRequest(val name: String)
data class AddItemRequest(val stockId: Long, val memo: String? = null)
