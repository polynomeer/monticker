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

// New: acks 는 실험 M-002 가 찾은 결함 D-M2-01 때문에 명시한다 — kafka-go Writer 의 RequiredAcks 기본값은 RequireNone(acks=0)
// 이다. 브로커 응답을 기다리지 않으니 같은 파티션에 보낸 배치가 뒤바뀌어 적재됐고(종목별 seq 35 가 34 보다 앞, 로그에서 확인.
// 12 파티션 기준 40초 실행의 약 1/3 에서 30~130건), 브로커 장애 때는 유실이 조용히 일어난다. RequireAll 은 복제 계수 1인
// 로컬에서는 acks=1 과 같고 운영(RF≥2, min.insync.replicas)에서 맞는 값이다. 실험이 기준선을 재현할 때만 KAFKA_REQUIRED_ACKS=none.
func New(brokers string, acks kafka.RequiredAcks) *Producer {
	return &Producer{
		writer: &kafka.Writer{
			Addr:         kafka.TCP(strings.Split(brokers, ",")...),
			Topic:        TicksTopic,
			Balancer:     &kafka.Hash{}, // same stockId key -> same partition, preserves per-stock order
			RequiredAcks: acks,
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
	// key=""(TICK_KEY_MODE=none)는 nil 키로 보낸다 — kafka.Hash는 nil 키만 라운드로빈으로
	// 돌리고, 빈 []byte는 해시돼 모든 틱이 한 파티션에 몰린다.
	var k []byte
	if key != "" {
		k = []byte(key)
	}
	return p.writer.WriteMessages(ctx, kafka.Message{
		Key:   k,
		Value: payload,
	})
}

// Close 는 모든 Publish 호출자가 멈춘 뒤에 불러야 한다. kafka-go 는 WriteMessages 와 Close 가 겹치면 Close 가 파티션 라이터
// 맵을 비운 다음에 새 파티션 라이터가 만들어질 수 있고, 그 고루틴은 아무도 닫지 않아 Close 의 WaitGroup.Wait 가 영원히
// 멈춘다(D-M2-02, goroutine 덤프로 확인). generator.RunWith 가 고루틴 종료를 기다려 주는 이유다.
func (p *Producer) Close() error {
	return p.writer.Close()
}

// ParseAcks: "none" | "one" | "all"(기본).
func ParseAcks(s string) kafka.RequiredAcks {
	switch strings.ToLower(s) {
	case "none", "0":
		return kafka.RequireNone
	case "one", "1":
		return kafka.RequireOne
	default:
		return kafka.RequireAll
	}
}
