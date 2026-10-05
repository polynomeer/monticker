package com.monticker.api.quant.application

import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.settlement.creator.application.CreatorEarningsService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class MonthlyNetPoint(val month: String, val net: BigDecimal)

data class CreatorStrategyRow(
    val marketId: Long,
    val rulesetId: String,
    val name: String,
    val price: BigDecimal,
    /** 현재 구독자 수(strategy_subscriptions 행 수) */
    val subscribers: Long,
    val thisMonthNet: BigDecimal,
    val totalNet: BigDecimal,
    val sharedAt: String?,
)

data class CreatorDashboard(
    /** 최근 12개월(KST, 이번 달 포함), 수익 없는 달은 0 */
    val monthly: List<MonthlyNetPoint>,
    val thisMonthNet: BigDecimal,
    val totalNet: BigDecimal,
    /** 내 전략 전체의 현재 구독자 수 합 */
    val activeSubscribers: Long,
    val strategies: List<CreatorStrategyRow>,
)

/**
 * /quant-lab/earnings 제작자 대시보드 집계. 수익 숫자는 settlement(CreatorEarningsService, 취소 제외),
 * 공유 전략·구독자 수는 quant가 가진 strategy_market·strategy_subscriptions, 전략 이름은 Mongo 룰셋에서
 * 모은다 — 이름 때문에 settlement가 quant를 알 수 없어(의존 방향 quant → settlement) 집계를 여기 둔다.
 * 이탈률은 구독 해지 이력을 남기지 않아(DELETE) 계산하지 않는다.
 */
@Service
class CreatorDashboardService(
    private val jdbc: JdbcTemplate,
    private val earnings: CreatorEarningsService,
    private val ruleSetRepository: RuleSetRepository,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        const val MONTHS = 12
    }

    fun dashboard(creatorId: Long, today: LocalDate = LocalDate.now(KST)): CreatorDashboard {
        val thisMonth = YearMonth.from(today)
        val firstMonth = thisMonth.minusMonths((MONTHS - 1).toLong())
        val monthStart = thisMonth.atDay(1).atStartOfDay(KST).toInstant()

        val byMonth = earnings.getMonthlyNet(creatorId, firstMonth.atDay(1).atStartOfDay(KST).toInstant())
            .associate { it.month to it.net }
        val monthly = (0 until MONTHS).map { i ->
            val ym = firstMonth.plusMonths(i.toLong()).toString() // YYYY-MM
            MonthlyNetPoint(ym, byMonth[ym] ?: BigDecimal.ZERO)
        }

        val net = earnings.getStrategyNet(creatorId, monthStart)

        val markets = jdbc.query(
            """SELECT sm.id, sm.ruleset_id, sm.price, sm.created_at,
                      (SELECT COUNT(*) FROM strategy_subscriptions ss WHERE ss.market_id = sm.id) AS subscribers
               FROM strategy_market sm
               WHERE sm.user_id = ?
               ORDER BY sm.created_at DESC""",
            { rs, _ -> MarketRow(
                id = rs.getLong("id"), rulesetId = rs.getString("ruleset_id"),
                price = rs.getBigDecimal("price") ?: BigDecimal.ZERO,
                createdAt = rs.getTimestamp("created_at")?.toInstant()?.toString(),
                subscribers = rs.getLong("subscribers"),
            ) },
            creatorId,
        )
        val names = ruleSetRepository.findAllById(markets.map { it.rulesetId }.distinct()).associate { it.id to it.name }

        val rows = markets.map { m ->
            CreatorStrategyRow(
                marketId     = m.id,
                rulesetId    = m.rulesetId,
                name         = names[m.rulesetId] ?: "(삭제된 전략)",
                price        = m.price,
                subscribers  = m.subscribers,
                thisMonthNet = net[m.id]?.sinceNet ?: BigDecimal.ZERO,
                totalNet     = net[m.id]?.total ?: BigDecimal.ZERO,
                sharedAt     = m.createdAt,
            )
        }

        return CreatorDashboard(
            monthly           = monthly,
            thisMonthNet      = net.values.fold(BigDecimal.ZERO) { a, v -> a + v.sinceNet },
            totalNet          = net.values.fold(BigDecimal.ZERO) { a, v -> a + v.total },
            activeSubscribers = rows.sumOf { it.subscribers },
            strategies        = rows,
        )
    }

    private data class MarketRow(val id: Long, val rulesetId: String, val price: BigDecimal, val createdAt: String?, val subscribers: Long)
}
