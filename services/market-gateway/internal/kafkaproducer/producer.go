package kafkaproducer

import (
	"context"
	"encoding/json"
	"strings"

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
