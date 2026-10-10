package com.monticker.api.brokerage.application

/**
 * 증권사 경로는 종목을 **코드(symbol)** 로 받는다. 그런데 `stocks`의 유니크 키는 `(symbol, market)`이라 같은 코드가 두 행일 수 있다
 * (이전상장 — KOSDAQ → KOSPI — 이면 옛 시장 행이 남는다. 로컬 DB엔 035420이 KOSDAQ·KOSPI 두 행이다).
 *
 * 예전 `SELECT id FROM stocks WHERE symbol = ?` + `queryForObject`는 두 행이면 예외였고, 모든 호출부가 `runCatching`으로 삼켜 null이 됐다:
 * 실주문은 "등록되지 않은 종목"으로 거절되고, **리스크 게이트의 보유 스냅샷에선 그 종목이 조용히 빠져** 집중도·보유 종목 수가
 * 낮게 잡혔다(게이트가 열리는 방향). 활성 행을, 그중 가장 최근에 생긴 행(이전상장이면 새 시장)을 고른다.
 */
const val STOCK_ID_BY_SYMBOL_SQL =
    "SELECT id FROM stocks WHERE symbol = ? ORDER BY is_active DESC, id DESC LIMIT 1"

/** [STOCK_ID_BY_SYMBOL_SQL]로 고른 종목의 최근 1분봉 종가. 예전엔 같은 코드의 모든 행 봉을 섞어 가장 최근 것을 골랐다. */
const val LATEST_CLOSE_BY_SYMBOL_SQL = """
    SELECT c.close FROM candles_1m c
    WHERE c.stock_id = (SELECT id FROM stocks WHERE symbol = ? ORDER BY is_active DESC, id DESC LIMIT 1)
    ORDER BY c.candle_time DESC LIMIT 1
"""
