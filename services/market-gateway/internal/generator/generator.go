// Package generator runs one goroutine per stock, producing a randomwalk
// tick every second. This is the Go-side equivalent of the Kotlin
// MockPriceGenerator — see ADR-005 for why tick *generation* (not just
// transport) lives here: it is the seam where real KIS WebSocket ingestion
// would plug in later without touching the Kafka/Kotlin side at all.
package generator

import (
	"context"
	"log"
	"math/rand"
	"strconv"
	"time"

	"monticker/market-gateway/internal/stock"
	"monticker/market-gateway/internal/tick"
)

type Publisher interface {
	Publish(ctx context.Context, key string, t tick.Tick) error
}

// Run starts one goroutine per stock and blocks until ctx is cancelled.
func Run(ctx context.Context, stocks []stock.Stock, pub Publisher, interval time.Duration) {
	for _, s := range stocks {
		go tickLoop(ctx, s, pub, interval)
	}
	<-ctx.Done()
}

func tickLoop(ctx context.Context, s stock.Stock, pub Publisher, interval time.Duration) {
	price := s.BasePrice
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			price = nextPrice(price)
			t := tick.Tick{
				StockID:     s.ID,
				Symbol:      s.Symbol,
				Market:      s.Market,
				Price:       round2(price),
				Volume:      randVolume(),
				TradeTime:   time.Now().UTC(),
				GeneratedAt: time.Now().UTC(),
			}
			if err := pub.Publish(ctx, keyOf(s.ID), t); err != nil {
				log.Printf("publish failed for %s: %v", s.Symbol, err)
			}
		}
	}
}

// nextPrice applies a small randomwalk step, mirroring the volatility
// bounds used by the Kotlin MockPriceGenerator (±0.5% per tick).
func nextPrice(price float64) float64 {
	const maxStepPct = 0.005
	step := (rand.Float64()*2 - 1) * maxStepPct
	next := price * (1 + step)
	if next < 1 {
		next = 1
	}
	return next
}

func randVolume() int64 {
	return int64(1000 + rand.Intn(49000))
}

func round2(v float64) float64 {
	return float64(int64(v*100)) / 100
}

func keyOf(stockID int64) string {
	return strconv.FormatInt(stockID, 10)
}
