package com.monticker.api.quant.api

import com.monticker.api.quant.application.StockSignalQueryService
import com.monticker.api.quant.application.StockSignalResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 종목 차트 "퀀트 시그널" 레이어 — 내 전략·구독 전략의 이 종목 신호(최신순). 로그인 필요. */
@RestController
@RequestMapping("/api/quant/stocks/{stockId}/signals")
class StockSignalController(private val service: StockSignalQueryService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping
    fun signals(
        @PathVariable stockId: Long,
        @RequestParam(defaultValue = "365") days: Int,
    ): ResponseEntity<List<StockSignalResponse>> = ResponseEntity.ok(service.signalsForStock(userId(), stockId, days))
}
