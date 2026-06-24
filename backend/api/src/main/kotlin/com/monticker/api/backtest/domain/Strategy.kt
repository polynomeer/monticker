package com.monticker.api.backtest.domain

interface BacktestStrategy {
    fun name(): String
    /** 각 봉에서 신호 반환. state에 전략별 상태 저장 가능 */
    fun onCandle(candle: DailyCandle, history: List<DailyCandle>, state: MutableMap<String, Any>): Signal
}
