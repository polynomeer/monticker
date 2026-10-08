package com.monticker.api.paper.domain

import com.monticker.api.common.domain.Money
import java.math.BigDecimal

/**
 * ADR-089 — 모의 계좌 시작 자금 화이트리스트. V86 `paper_accounts_initial_capital_chk`와 같은 값이어야 한다.
 * 화면 버튼(1,000만·3,000만·1억)에 대응한다. 임의 금액을 받지 않는다 — 원장 대사(ADR-043)의 "초기 지급" 항이
 * 이 값이고, 큰 수·소수·음수를 열어 두면 대사·리스크 한도(현금 대비 비율)가 모두 흔들린다.
 */
object PaperInitialCapital {
    val ALLOWED: List<BigDecimal> = listOf(
        BigDecimal("10000000"),
        BigDecimal("30000000"),
        BigDecimal("100000000"),
    )

    /** 허용값이면 [Money]로, 아니면 400(IllegalArgumentException). 30000000.5처럼 정수가 아닌 값도 거부한다. */
    fun parse(value: BigDecimal?): Money {
        requireNotNull(value) { "initialCapital은 필수입니다" }
        val match = ALLOWED.firstOrNull { it.compareTo(value) == 0 }
            ?: throw IllegalArgumentException("initialCapital은 ${ALLOWED.joinToString { it.toPlainString() }} 중 하나여야 합니다")
        return Money(match)
    }
}
