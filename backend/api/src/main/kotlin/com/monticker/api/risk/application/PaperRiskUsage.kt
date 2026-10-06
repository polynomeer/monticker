package com.monticker.api.risk.application

import com.monticker.api.risk.domain.RiskLimit
import org.springframework.stereotype.Component
import java.math.BigDecimal

/** 한도 하나의 현재 사용 상태. [ratio] = current / limit (1.0이면 한도에 닿았다). */
data class RuleUsage(
    val rule: String,
    val label: String,
    val current: Double,
    val limit: Double,
    /** 보조 설명(예: 집중도가 가장 높은 종목·섹터) — 알림 본문에 쓴다. */
    val subject: String? = null,
) {
    val ratio: Double get() = if (limit > 0) current / limit else 0.0
}

/**
 * ADR-070 — 모의계좌의 한도 사용률. 매수 게이트(RiskRuleQueryService)와 같은 입력(paperSnapshot·최근가·역사적 VaR)과 같은
 * 기준 금액을 쓴다: 집중도 분모는 현금+예약금+보유 평가액, 일간 손실 기준은 현금+예약금(게이트의 모의투자 기준과 같다).
 *
 * 값을 모르는 것은 "안전"으로도 "근접"으로도 판정하지 않고 뺀다 — 가격 없는 보유가 있으면 집중도는 건너뛴다.
 * 경고는 주문을 막지 않으므로, 모르면 막는 게이트와 달리 근거 없는 경고를 내지 않는 쪽이 맞다.
 */
@Component
class PaperRiskUsage(
    private val rules: RiskRuleQueryService,
) {
    fun evaluate(userId: Long, limits: RiskLimit): List<RuleUsage> {
        val snap = rules.paperSnapshot(userId)
        val out = mutableListOf<RuleUsage>()

        // 일간 손실 — 오늘 실현 손실 / (현금 × 한도%)
        val cash = snap.cashAssets.toDouble()   // 가용 현금 + 미체결 매수 예약금 — 게이트의 모의투자 기준과 같다(ADR-074 Note)
        if (cash > 0 && snap.dailyPnl < BigDecimal.ZERO) {
            out += RuleUsage("DAILY_LOSS", "일일 손실", -snap.dailyPnl.toDouble() / cash * 100, limits.dailyLossLimitPct.toDouble())
        }

        if (snap.holdings.isEmpty()) return out

        out += RuleUsage("VAR", "1일 VaR", rules.historicalVaRPct(snap.holdings.map { it.stockId }), limits.varLimitPct.toDouble())

        val values = snap.holdings.associate { it.stockId to rules.currentPrice(it.stockId).multiply(BigDecimal(it.qty)).toDouble() }
        if (values.values.any { it <= 0.0 }) return out
        val total = cash.coerceAtLeast(0.0) + values.values.sum()
        if (total <= 0) return out

        val top = values.maxBy { it.value }
        out += RuleUsage(
            "CONCENTRATION", "단일 종목 집중도", top.value / total * 100, limits.concentrationLimitPct.toDouble(),
            subject = rules.symbolOf(top.key),
        )

        // 섹터 집중도 — 한도를 설정한 경우만(ADR-069). 미분류 종목은 어떤 섹터에도 넣지 않는다.
        limits.sectorConcentrationLimitPct?.let { sectorLimit ->
            val sectors = rules.sectorsOf(values.keys)
            values.entries.filter { it.key in sectors }
                .groupBy({ sectors.getValue(it.key) }, { it.value })
                .mapValues { it.value.sum() }
                .maxByOrNull { it.value }
                ?.let { (sector, v) -> out += RuleUsage("SECTOR_CONCENTRATION", "섹터 집중도", v / total * 100, sectorLimit.toDouble(), sector) }
        }
        return out
    }
}
