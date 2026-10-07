package com.monticker.api.marketdata.api

import com.monticker.api.marketdata.application.RecentTrade
import com.monticker.api.marketdata.application.RecentTradesBuffer
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 종목별 최근 체결 틱(최신순). 이 api pod가 받은 틱의 링 버퍼이며 영속 이력이 아니다 — [RecentTradesBuffer]. */
@RestController
@RequestMapping("/api/stocks")
class RecentTradesController(private val buffer: RecentTradesBuffer) {

    @GetMapping("/{stockId}/trades")
    fun recentTrades(
        @PathVariable stockId: Long,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<List<RecentTrade>> = ResponseEntity.ok(buffer.recent(stockId, limit))
}
