package tick

import (
	"encoding/json"
	"testing"
	"time"
)

// TestWireFormat pins the JSON this gateway publishes to market.ticks. The Kotlin
// side (backend/worker WireFormatGoldenTest, goTickJson) reads exactly this string,
// so a change here must be mirrored there — there is no schema registry.
func TestWireFormat(t *testing.T) {
	tick := Tick{
		StockID:     5930,
		Symbol:      "005930",
		Market:      "KOSPI",
		Price:       71200.5,
		Volume:      1200,
		TradeTime:   time.Date(2026, 10, 16, 1, 0, 0, 123456789, time.UTC),
		GeneratedAt: time.Date(2026, 10, 16, 1, 0, 0, 200000000, time.UTC),
		Seq:         42,
		Source:      "KIS",
	}
	got, err := json.Marshal(tick)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"stockId":5930,"symbol":"005930","market":"KOSPI","price":71200.5,"volume":1200,` +
		`"tradeTime":"2026-10-16T01:00:00.123456789Z","generatedAt":"2026-10-16T01:00:00.2Z","seq":42,"source":"KIS"}`
	if string(got) != want {
		t.Fatalf("wire format changed\n got: %s\nwant: %s", got, want)
	}
}
