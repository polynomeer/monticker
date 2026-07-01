// Package stock loads the active stock universe from PostgreSQL once at
// startup. The gateway does not watch for new stocks at runtime — see the
// "한계와 트레이드오프" section of docs/technical/kafka-tick-pipeline.md.
package stock

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Stock struct {
	ID        int64
	Symbol    string
	Market    string
	BasePrice float64
}

func LoadActiveStocks(ctx context.Context, pool *pgxpool.Pool) ([]Stock, error) {
	rows, err := pool.Query(ctx, `
		SELECT s.id, s.symbol, s.market,
		       COALESCE(
		           (SELECT close FROM candles_1m c WHERE c.stock_id = s.id ORDER BY c.candle_time DESC LIMIT 1),
		           CASE WHEN s.market IN ('NASDAQ', 'NYSE') THEN 150 ELSE 50000 END
		       ) AS base_price
		FROM stocks s
		WHERE s.is_active = true
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var stocks []Stock
	for rows.Next() {
		var s Stock
		if err := rows.Scan(&s.ID, &s.Symbol, &s.Market, &s.BasePrice); err != nil {
			return nil, err
		}
		stocks = append(stocks, s)
	}
	return stocks, rows.Err()
}
