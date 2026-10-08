package com.monticker.api.wallet.equity

import java.math.BigDecimal

/**
 * ADR-095 — 모의 계좌 평가자산 = 사용 가능 현금 + 미체결 매수 예약금 + 보유 평가액(최신 1분봉 종가).
 * /wallet 총자산(`WalletMapResponse.totalAssets`)과 **같은 정의**다(ADR-091). 정산 대기 수수료·세금은 빼지 않는다.
 */
interface PaperEquityQuery {
    fun paperEquity(userId: Long): BigDecimal
}
