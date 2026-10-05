package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.MarketIndexClose
import com.monticker.api.marketdata.domain.MarketIndexQuote
import com.monticker.api.marketdata.infrastructure.MarketIndexRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-071 — 지수·환율 조회. 값은 worker가 수집하고 여기서는 읽기만 한다.
 * 화면 순서(KOSPI → KOSDAQ → USDKRW)를 서버가 정해 준다.
 */
@Service
class MarketIndexService(private val repo: MarketIndexRepository) {

    companion object {
        val DISPLAY_ORDER = listOf("KOSPI", "KOSDAQ", "USDKRW")
        /** 카드 스파크라인용 최근 일봉 수 */
        const val SPARK_DAYS = 30
        /** 일봉 조회 한 번의 최대 행 수(약 3년치 거래일) */
        const val MAX_CLOSES = 800
        private val KST = ZoneId.of("Asia/Seoul")
    }

    fun getIndices(today: LocalDate = LocalDate.now(KST)): List<MarketIndexWithSpark> {
        val quotes = repo.findQuotes().associateBy { it.code }
        return DISPLAY_ORDER.mapNotNull { quotes[it] }.map { q ->
            val closes = repo.findCloses(q.code, today.minusDays(SPARK_DAYS * 2L), today, SPARK_DAYS)
            MarketIndexWithSpark(q, closes)
        }
    }

    fun getCloses(code: String, from: LocalDate, to: LocalDate): List<MarketIndexClose> {
        require(code in DISPLAY_ORDER) { "알 수 없는 지수 코드: $code" }
        require(!from.isAfter(to)) { "from이 to보다 늦습니다" }
        return repo.findCloses(code, from, to, MAX_CLOSES)
    }
}

data class MarketIndexWithSpark(val quote: MarketIndexQuote, val closes: List<MarketIndexClose>)
