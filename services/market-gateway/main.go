// monticker market-gateway — generates per-stock tick streams (one goroutine
// per stock) and publishes them to Kafka. See ADR-005 and
// docs/technical/kafka-tick-pipeline.md for the reasoning behind writing
// this component in Go rather than the existing Kotlin stack.
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"monticker/market-gateway/internal/generator"
	"monticker/market-gateway/internal/kafkaproducer"
	"monticker/market-gateway/internal/stock"
)

func main() {
	brokers := getenv("KAFKA_BROKERS", "localhost:9092")
	dbURL := getenv("DB_URL", "postgres://monticker:monticker@localhost:5432/monticker?sslmode=disable")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		log.Fatalf("db connect failed: %v", err)
	}
	defer pool.Close()

	stocks, err := stock.LoadActiveStocks(ctx, pool)
	if err != nil {
		log.Fatalf("load stocks failed: %v", err)
	}
	if len(stocks) == 0 {
		log.Fatal("no active stocks found — is the DB seeded? (see V12__seed_stocks.sql)")
	}
	log.Printf("market-gateway: loaded %d active stocks", len(stocks))

	producer := kafkaproducer.New(brokers)
	defer producer.Close()

	// TICK_INTERVAL_MS: 종목당 틱 간격. 기본 1000ms(=종목 수 tick/s). 부하 시나리오 L-03(tick-storm)이
	// 20ms 등으로 낮춰 초당 수만 틱을 만든다 — resilience-plan §5.1.
	interval := 1000 * time.Millisecond
	if v, err := strconv.Atoi(getenv("TICK_INTERVAL_MS", "1000")); err == nil && v > 0 {
		interval = time.Duration(v) * time.Millisecond
	}
	log.Printf("market-gateway: publishing to kafka brokers=%s topic=%s interval=%s (~%d tick/s)",
		brokers, kafkaproducer.TicksTopic, interval, int(float64(len(stocks))/interval.Seconds()))

	// 실험 스위치(reports/M-002, bench/experiments/) — 기본값은 전부 꺼져 있고 와이어 포맷·키는 그대로다.
	//   TICK_SEQ=true            종목별 시퀀스 번호(seq)를 채운다 → worker TickOrderMonitor가 순서 위반을 센다
	//   TICK_KEY_MODE=none       키 없이 발행(파티션 라운드로빈) — "키=stockId vs 라운드로빈" 비교의 대조군
	//   TICK_HOT_STOCK_ID=2 TICK_HOT_INTERVAL_MS=1   한 종목만 폭주시킨다 — 핫 종목 head-of-line 실험
	opt := generator.Options{
		Seq:     getenv("TICK_SEQ", "false") == "true",
		KeyNone: getenv("TICK_KEY_MODE", "stock") == "none",
	}
	if v, err := strconv.ParseInt(getenv("TICK_HOT_STOCK_ID", "0"), 10, 64); err == nil && v > 0 {
		opt.HotStockID = v
		if ms, err := strconv.Atoi(getenv("TICK_HOT_INTERVAL_MS", "10")); err == nil && ms > 0 {
			opt.HotInterval = time.Duration(ms) * time.Millisecond
		}
	}
	if opt.Seq || opt.KeyNone || opt.HotStockID != 0 {
		log.Printf("market-gateway: EXPERIMENT seq=%v keyNone=%v hotStock=%d hotInterval=%s", opt.Seq, opt.KeyNone, opt.HotStockID, opt.HotInterval)
	}
	generator.RunWith(ctx, stocks, producer, interval, opt)
	log.Println("market-gateway: shutting down")
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
