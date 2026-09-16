// Package tick defines the wire format shared with the Kotlin Worker's
// Kafka consumer. Keep this struct's JSON field names in sync with
// GeneratedTick in backend/worker — there is no schema registry (see
// docs/technical/kafka-tick-pipeline.md "한계와 트레이드오프").
package tick

import "time"

type Tick struct {
	StockID     int64     `json:"stockId"`
	Symbol      string    `json:"symbol"`
	Market      string    `json:"market"`
	Price       float64   `json:"price"`
	Volume      int64     `json:"volume"`
	TradeTime   time.Time `json:"tradeTime"`
	GeneratedAt time.Time `json:"generatedAt"`
	// Seq: 종목별 단조 증가 시퀀스(1부터). 실험 M-002(파티션×컨슈머 순서 보장)에서 worker의
	// TickOrderMonitor가 순서 위반·중복·유실을 세는 근거다. TICK_SEQ=true 일 때만 채워지고,
	// 0이면 JSON에서 빠진다(omitempty) — 기본 와이어 포맷은 그대로다.
	Seq int64 `json:"seq,omitempty"`
}
