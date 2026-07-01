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
}
