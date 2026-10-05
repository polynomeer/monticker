package com.monticker.api.quant.application

import com.monticker.api.quant.domain.QuantAuxData
import com.monticker.api.quant.domain.RuleDefinition
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * ADR-079 — 일봉 밖 데이터로 계산하는 지표. 데이터가 실제로 쌓이는 것만 지원한다.
 * - NEWS_SENTIMENT: news_articles.sentiment(워커 NewsSentimentAnalyzer가 기록)
 * - DISCLOSURE: stock_events DISCLOSURE_PUBLISHED(DART 수집기가 기록, metadata.reportName)
 * 시가총액은 종목당 최신 스냅샷 1행뿐이라(stock_fundamentals) 과거 시점 값을 알 수 없어 지원하지 않는다.
 */
object AuxIndicators {
    const val NEWS_SENTIMENT = "NEWS_SENTIMENT"
    const val DISCLOSURE = "DISCLOSURE"

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)

    /** 공시 유형 — 조건의 comparator 자리에 들어간다(MACD의 GOLDEN/DEAD처럼). */
    val DISCLOSURE_CATEGORIES = setOf("ANY", "EARNINGS", "BUYBACK", "RIGHTS_ISSUE", "BONUS_ISSUE", "MNA", "INSIDER", "DIVIDEND")

    /** 장 마감 이후 나온 정보는 다음 날부터 쓸 수 있다. */
    fun availableDate(at: Instant): LocalDate {
        val local = at.atZone(KST)
        return if (local.toLocalTime().isBefore(MARKET_CLOSE)) local.toLocalDate() else local.toLocalDate().plusDays(1)
    }

    /** DART 보고서명 → 유형 집합. 정정 공시("[기재정정]…")도 같은 유형으로 본다. */
    fun classifyDisclosure(reportName: String): Set<String> {
        val n = reportName.replace(" ", "")
        val out = mutableSetOf("ANY")
        if (listOf("사업보고서", "반기보고서", "분기보고서", "영업(잠정)실적", "잠정실적", "매출액또는손익구조").any { n.contains(it) }) out += "EARNINGS"
        if (n.contains("자기주식") && n.contains("취득")) out += "BUYBACK"
        if (n.contains("유상증자")) out += "RIGHTS_ISSUE"
        if (n.contains("무상증자")) out += "BONUS_ISSUE"
        if (listOf("합병", "분할", "영업양수", "영업양도", "주식교환", "인수").any { n.contains(it) }) out += "MNA"
        if (n.contains("임원") || n.contains("주요주주")) out += "INSIDER"
        if (n.contains("배당")) out += "DIVIDEND"
        return out
    }

    fun uses(ruleDef: RuleDefinition, indicator: String): Boolean =
        (ruleDef.entryRules.conditions + ruleDef.exitRules.conditions).any { it.indicator.equals(indicator, ignoreCase = true) }

    /** (candles[idx-period].date, candles[idx].date] — 직전 창 마지막 거래일 다음 날부터 오늘까지(주말·휴일 포함). */
    internal fun window(dates: List<LocalDate>, idx: Int, period: Int): ClosedRange<LocalDate>? {
        if (period < 1 || idx - period < 0) return null
        return dates[idx - period].plusDays(1)..dates[idx]
    }

    /** 순감성 = (긍정 - 부정) / 감성이 붙은 기사 수. 창 안에 기사가 없으면 null(조건 불충족). */
    fun netSentiment(aux: QuantAuxData, range: ClosedRange<LocalDate>): Double? {
        var pos = 0; var neg = 0; var total = 0
        for ((d, c) in aux.sentimentByDate) {
            if (d in range) { pos += c.positive; neg += c.negative; total += c.total }
        }
        return if (total == 0) null else (pos - neg).toDouble() / total
    }

    fun hasDisclosure(aux: QuantAuxData, range: ClosedRange<LocalDate>, category: String): Boolean =
        aux.disclosuresByDate.any { (d, cats) -> d in range && category.uppercase() in cats }
}
