package kafkaproducer

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	kafka "github.com/segmentio/kafka-go"

	"monticker/market-gateway/internal/tick"
)

const TicksTopic = "market.ticks"

type Producer struct {
	writer *kafka.Writer
}

func New(brokers string) *Producer {
	return &Producer{
		writer: &kafka.Writer{
			Addr:     kafka.TCP(strings.Split(brokers, ",")...),
			Topic:    TicksTopic,
			Balancer: &kafka.Hash{}, // same stockId key -> same partition, preserves per-stock order
			// WriteMessages는 동기다: 배치(BatchSize 100)가 차거나 BatchTimeout이 지나야 반환한다. 기본 1초.
			// 파티션이 여러 개면(ADR-040) 파티션당 배치가 100건을 못 채워 매 호출이 1초를 기다리고, 종목당
			// 고루틴 1개인 이 구조에서는 처리량이 종목당 1 msg/s로 붕괴한다 — 12 파티션에서 L-03이 200 tick/s로
			// 떨어지며 발견했다. 10ms면 배치 효율은 유지하면서 부분 배치가 곧바로 나간다.
			BatchTimeout: 10 * time.Millisecond,
		},
	}
}

func (p *Producer) Publish(ctx context.Context, key string, t tick.Tick) error {
	payload, err := json.Marshal(t)
	if err != nil {
		return err
	}
	return p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(key),
		Value: payload,
	})
}

func (p *Producer) Close() error {
	return p.writer.Close()
}
