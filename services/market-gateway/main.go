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
	generator.Run(ctx, stocks, producer, interval)
	log.Println("market-gateway: shutting down")
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
