package com.monticker.api.stockscore.api

import com.monticker.api.stockscore.application.StockScoreResponse
import com.monticker.api.stockscore.application.StockScoreService
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
class StockScoreController(private val stockScoreService: StockScoreService) {

    /**
     * 종목 스코어(밸류에이션/성장성/과거실적/재무건전성/배당) 조회 — ADR-020.
     *
     * GET /api/stocks/{stockId}/score
     */
    @GetMapping("/api/stocks/{stockId}/score")
    fun getScore(@PathVariable stockId: Long): ResponseEntity<StockScoreResponse> =
        ResponseEntity.ok(stockScoreService.getScore(stockId))
}
