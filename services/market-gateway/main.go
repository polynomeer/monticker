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

	log.Printf("market-gateway: publishing to kafka brokers=%s topic=%s", brokers, kafkaproducer.TicksTopic)
	generator.Run(ctx, stocks, producer, 1*time.Second)
	log.Println("market-gateway: shutting down")
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
