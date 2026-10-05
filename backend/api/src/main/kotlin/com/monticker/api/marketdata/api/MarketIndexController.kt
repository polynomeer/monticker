package com.monticker.api.marketdata.api

import com.monticker.api.marketdata.application.MarketIndexService
import com.monticker.api.marketdata.application.MarketIndexWithSpark
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-071 — 지수·환율(KOSPI·KOSDAQ·USD/KRW). 비로그인 공개.
 * isMocked=true면 개발용 Mock 공급자 값이다 — 화면은 실시세처럼 보여주지 않는다.
 */
@RestController
@RequestMapping("/api/market/indices")
class MarketIndexController(private val service: MarketIndexService) {

    /** GET /api/market/indices — 최신 시세 + 최근 30거래일 종가 */
    @GetMapping
    fun indices(): ResponseEntity<List<MarketIndexResponse>> =
        ResponseEntity.ok(service.getIndices().map { MarketIndexResponse.from(it) })

    /** GET /api/market/indices/KOSPI/daily?from=2025-10-01&to=2026-10-05 — 일별 종가(최대 800행) */
    @GetMapping("/{code}/daily")
    fun daily(
        @PathVariable code: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<List<MarketIndexCloseResponse>> {
        val end = to ?: LocalDate.now(ZoneId.of("Asia/Seoul"))
        val start = from ?: end.minusYears(1)
        return try {
            ResponseEntity.ok(service.getCloses(code.uppercase(), start, end).map {
                MarketIndexCloseResponse(it.date, it.close, it.isMocked)
            })
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }
}

data class MarketIndexResponse(
    val code: String,
    val name: String,
    val value: BigDecimal,
    val prevClose: BigDecimal?,
    val change: BigDecimal?,
    val changeRate: Double?,
    val asOf: Instant,
    val source: String,
    val isMocked: Boolean,
    /** 최근 거래일 종가(오래된 → 최근). 카드 스파크라인용 */
    val closes: List<BigDecimal>,
) {
    companion object {
        fun from(w: MarketIndexWithSpark) = MarketIndexResponse(
            code       = w.quote.code,
            name       = w.quote.name,
            value      = w.quote.value,
            prevClose  = w.quote.prevClose,
            change     = w.quote.change,
            changeRate = w.quote.changeRate,
            asOf       = w.quote.asOf,
            source     = w.quote.source,
            isMocked   = w.quote.isMocked,
            closes     = w.closes.map { it.close },
        )
    }
}

data class MarketIndexCloseResponse(val date: LocalDate, val close: BigDecimal, val isMocked: Boolean)
