package com.monticker.api.marketdata.api

import com.monticker.api.marketdata.application.IntradaySeries
import com.monticker.api.marketdata.application.IntradaySeriesService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 여러 종목의 장중 미니 시계열(스파크라인용). 비로그인 공개.
 *
 * GET /api/market/intraday?ids=1,2,3   (최대 50개, 초과분은 무시)
 */
@RestController
@RequestMapping("/api/market/intraday")
class IntradaySeriesController(private val service: IntradaySeriesService) {

    @GetMapping
    fun intraday(@RequestParam ids: String): ResponseEntity<List<IntradaySeries>> {
        val stockIds = IntradaySeriesService.normalizeIds(ids.split(",").mapNotNull { it.trim().toLongOrNull() })
        return ResponseEntity.ok(service.getSeries(stockIds))
    }
}
