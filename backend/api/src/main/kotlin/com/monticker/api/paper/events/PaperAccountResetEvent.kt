package com.monticker.api.paper.events

import java.math.BigDecimal

/**
 * ADR-043 — 모의투자 계좌 초기화는 현금 컬럼을 바꾸는 경로다. 원장에 남지 않으면
 * "잔고 + 예약금 = 초기 지급 + 원장 합" 대사가 초기화한 유저마다 영구히 어긋난다.
 * wallet이 이 이벤트를 받아 (초기 잔고 − 직전 잔고)를 DEPOSIT/WITHDRAWAL로 기록한다.
 */
data class PaperAccountResetEvent(
    val userId: Long,
    val previousCash: BigDecimal,
    val newCash: BigDecimal,
)
