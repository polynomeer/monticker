package com.monticker.worker.notification

/**
 * ADR-108 — 한 종목의 소식(감지 이벤트·뉴스·공시)을 받을 사람: 그 종목을 **관심종목에 넣었거나 어느 계좌에든 보유 중인** 사용자.
 * 소식을 보내는 두 경로(`StockEventWriter` 푸시, `NewsAlertFanout`)가 같은 기준을 쓰도록 여기 한 곳에 둔다.
 *
 * 보유는 통합 포트폴리오 원장(portfolio_transactions, ADR-106)으로 정한다. 모의계좌(paper)는 넣지 않는다 — 모의 자금이다.
 * 수량은 api `HoldingsCalculator`와 같은 규칙이다: 계좌·통화별로 날짜, 같은 날이면 id 순서로 더하고, 가진 것보다 많이 판 매도는
 * 가진 만큼만 뺀다(0 아래로 내려가지 않는다). 이 "0에서 멈추는 누적합"의 마지막 값은 `합계 − min(0, 누적합의 최솟값)`이라
 * 창 함수 한 번으로 같은 값을 낸다.
 *
 * 탈퇴 여부·기기 토큰·알림 설정은 부르는 쪽이 붙인다. 바인딩: stockId, stockId. 결과 열: user_id(사용자당 한 번).
 */
object StockAudience {

    const val INTERESTED_USERS_SQL = """
        SELECT wg.user_id
        FROM watchlist_items wi
        JOIN watchlist_groups wg ON wg.id = wi.group_id
        WHERE wi.stock_id = ?
        UNION
        SELECT h.user_id
        FROM (
            SELECT pa.user_id, pt.account_id, pt.currency,
                   CASE WHEN pt.type = 'BUY' THEN pt.quantity ELSE -pt.quantity END AS delta,
                   SUM(CASE WHEN pt.type = 'BUY' THEN pt.quantity ELSE -pt.quantity END)
                       OVER (PARTITION BY pt.account_id, pt.currency ORDER BY pt.trade_date, pt.id) AS running
            FROM portfolio_transactions pt
            JOIN portfolio_accounts pa ON pa.id = pt.account_id
            WHERE pt.stock_id = ? AND pt.type IN ('BUY', 'SELL')
        ) h
        GROUP BY h.user_id, h.account_id, h.currency
        HAVING SUM(h.delta) - LEAST(0, MIN(h.running)) > 0
    """
}
