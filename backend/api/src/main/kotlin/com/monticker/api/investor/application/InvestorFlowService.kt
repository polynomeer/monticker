package com.monticker.api.investor.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 개인·외국인·기관 순매수(투자자 동향) 조회 — ADR-017.
 * KRX 데이터라 KOSPI/KOSDAQ 종목에만 값이 존재한다.
 */
@Service
class InvestorFlowService(private val jdbc: JdbcTemplate) {

    fun getFlow(stockId: Long, days: Int): InvestorFlowResult {
        val rows = jdbc.query(
            """
            SELECT trade_date, individual_net_qty, foreign_net_qty, institution_net_qty,
                   individual_net_amount, foreign_net_amount, institution_net_amount, is_mocked
            FROM investor_flow
            WHERE stock_id = ?
            ORDER BY trade_date DESC
            LIMIT ?
            """,
            { rs, _ ->
                InvestorFlowDay(
                    tradeDate            = rs.getDate("trade_date").toLocalDate(),
                    individualNetQty     = rs.getLong("individual_net_qty"),
                    foreignNetQty        = rs.getLong("foreign_net_qty"),
                    institutionNetQty    = rs.getLong("institution_net_qty"),
                    individualNetAmount  = rs.getLong("individual_net_amount"),
                    foreignNetAmount     = rs.getLong("foreign_net_amount"),
                    institutionNetAmount = rs.getLong("institution_net_amount"),
                    isMocked             = rs.getBoolean("is_mocked"),
                )
            },
            stockId, days.coerceIn(1, 60),
        )

        return InvestorFlowResult(
            stockId = stockId,
            days = rows,
            isAnyMocked = rows.any { it.isMocked },
        )
    }
}

data class InvestorFlowDay(
    val tradeDate: LocalDate,
    val individualNetQty: Long,
    val foreignNetQty: Long,
    val institutionNetQty: Long,
    val individualNetAmount: Long,
    val foreignNetAmount: Long,
    val institutionNetAmount: Long,
    val isMocked: Boolean,
)

data class InvestorFlowResult(
    val stockId: Long,
    val days: List<InvestorFlowDay>,
    val isAnyMocked: Boolean,
)
