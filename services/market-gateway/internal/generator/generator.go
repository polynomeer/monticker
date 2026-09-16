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

// Options: 실험 전용 스위치(reports/M-002). 전부 0값이면 기본 동작이다 — 키=stockId, seq 없음, 핫 종목 없음.
type Options struct {
	Seq         bool          // 종목별 시퀀스 번호를 채운다 (TICK_SEQ)
	KeyNone     bool          // 키 없이(nil) 발행해 파티션을 라운드로빈으로 돌린다 (TICK_KEY_MODE=none)
	HotStockID  int64         // 이 종목만 HotInterval 로 발행한다 (TICK_HOT_STOCK_ID)
	HotInterval time.Duration // 핫 종목의 틱 간격 (TICK_HOT_INTERVAL_MS)
}

// Run starts one goroutine per stock and blocks until ctx is cancelled.
func Run(ctx context.Context, stocks []stock.Stock, pub Publisher, interval time.Duration) {
	RunWith(ctx, stocks, pub, interval, Options{})
}

func RunWith(ctx context.Context, stocks []stock.Stock, pub Publisher, interval time.Duration, opt Options) {
	for _, s := range stocks {
		iv := interval
		if opt.HotStockID != 0 && s.ID == opt.HotStockID && opt.HotInterval > 0 {
			iv = opt.HotInterval
		}
		go tickLoop(ctx, s, pub, iv, opt)
	}
	<-ctx.Done()
}

func tickLoop(ctx context.Context, s stock.Stock, pub Publisher, interval time.Duration, opt Options) {
	price := s.BasePrice
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	var seq int64

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
			if opt.Seq {
				seq++
				t.Seq = seq
			}
			key := keyOf(s.ID)
			if opt.KeyNone {
				key = ""
			}
			if err := pub.Publish(ctx, key, t); err != nil {
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
