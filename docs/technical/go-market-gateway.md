# Go Market Gateway — 설계 및 구현

> 관련: [ADR-005](../decisions/005-kafka-go-gateway-netty-broadcast.md), [kafka-tick-pipeline.md](./kafka-tick-pipeline.md)

## 1. 역할

`services/market-gateway` 는 활성 종목 목록을 DB에서 읽고, 종목별로 goroutine을 하나씩 띄워 1초마다 가격 틱을 생성한 뒤 Kafka `market.ticks` 토픽에 발행한다. 현재는 랜덤워크 가격을 사용하지만, goroutine 내부를 KIS WebSocket 수신 루프로 교체하면 실시간 시세 수집기로 전환된다.

---

## 2. 디렉터리 구조

```
services/market-gateway/
├── main.go
├── go.mod                            # module monticker/market-gateway
├── Dockerfile
└── internal/
    ├── stock/
    │   └── loader.go                 # pgxpool로 stocks 테이블에서 활성 종목 로드
    ├── generator/
    │   └── generator.go              # goroutine-per-stock 틱 생성 루프
    ├── kafkaproducer/
    │   └── producer.go               # kafka-go Writer 래퍼
    └── tick/
        └── tick.go                   # Tick 구조체 정의
```

---

## 3. 진입점 (`main.go`)

```go
func main() {
    brokers := getenv("KAFKA_BROKERS", "localhost:9092")
    dbURL   := getenv("DB_URL", "postgres://monticker:monticker@localhost:5432/monticker?sslmode=disable")

    ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
    defer stop()

    pool, err := pgxpool.New(ctx, dbURL)
    // ... error handling

    stocks, err := stock.LoadActiveStocks(ctx, pool)
    producer := kafkaproducer.New(brokers)
    defer producer.Close()

    generator.Run(ctx, stocks, producer, 1*time.Second)
}
```

핵심 선택:
- **`signal.NotifyContext`** — SIGINT/SIGTERM 수신 시 ctx를 취소해 모든 goroutine이 정상 종료된다.
- **pgxpool** — goroutine이 DB 커넥션을 공유할 필요가 없도록 커넥션 풀로 시작 시 1회 조회만 수행한다. 이후 DB 접근 없음.

---

## 4. 종목 로드 (`stock/loader.go`)

```go
func LoadActiveStocks(ctx context.Context, pool *pgxpool.Pool) ([]Stock, error) {
    rows, err := pool.Query(ctx, `
        SELECT id, symbol, market, base_price
        FROM stocks
        WHERE is_active = true
        ORDER BY id
    `)
    // ...
}
```

`base_price` 컬럼을 시작 가격으로 사용해 각 종목의 랜덤워크 시드를 다르게 한다. 202개 종목(V12 seed) 기준 약 1~2ms에 완료된다.

---

## 5. 틱 생성 루프 (`generator/generator.go`)

```go
func Run(ctx context.Context, stocks []stock.Stock, pub Publisher, interval time.Duration) {
    var wg sync.WaitGroup
    for _, s := range stocks {
        wg.Add(1)
        go func(s stock.Stock) {
            defer wg.Done()
            tickLoop(ctx, s, pub, interval)
        }(s)
    }
    wg.Wait()
}

func tickLoop(ctx context.Context, s stock.Stock, pub Publisher, interval time.Duration) {
    price := s.BasePrice
    ticker := time.NewTicker(interval)
    defer ticker.Stop()
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
            pub.Publish(ctx, strconv.FormatInt(s.ID, 10), t)
        }
    }
}
```

### 랜덤워크 가격

```go
func nextPrice(current float64) float64 {
    change := (rand.Float64() - 0.5) * 0.002  // ±0.1% 변동
    return math.Max(current*(1+change), 1.0)
}
```

실거래소 연동 시 이 함수를 KIS WebSocket 메시지 파싱으로 교체한다.

---

## 6. Kafka 발행 (`kafkaproducer/producer.go`)

```go
type Producer struct {
    writer *kafka.Writer
}

func New(brokers string) *Producer {
    return &Producer{
        writer: &kafka.Writer{
            Addr:         kafka.TCP(strings.Split(brokers, ",")...),
            Topic:        "market.ticks",
            Balancer:     &kafka.Hash{},       // key(stockId) 해시로 같은 파티션에 발행
            RequiredAcks: kafka.RequireOne,
        },
    }
}

func (p *Producer) Publish(ctx context.Context, key string, t tick.Tick) {
    payload, _ := json.Marshal(t)
    p.writer.WriteMessages(ctx, kafka.Message{
        Key:   []byte(key),
        Value: payload,
    })
}
```

**`kafka.Hash{}` 밸런서** — `key = stockId` 를 해싱해 동일 종목의 틱이 항상 같은 파티션으로 들어간다. 컨슈머 측에서 파티션별 순서를 보장받을 수 있어 캔들 집계 오류를 방지한다.

---

## 7. Tick 메시지 형식

```go
type Tick struct {
    StockID     int64     `json:"stockId"`
    Symbol      string    `json:"symbol"`
    Market      string    `json:"market"`
    Price       float64   `json:"price"`
    Volume      int64     `json:"volume"`
    TradeTime   time.Time `json:"tradeTime"`
    GeneratedAt time.Time `json:"generatedAt"`
}
```

JSON으로 직렬화해 Kafka 메시지 value에 저장한다. 스키마 레지스트리(Avro)는 미적용 — 규모 확장 시 도입 고려.

---

## 8. 의존성 (`go.mod`)

```
module monticker/market-gateway

go 1.22

require (
    github.com/jackc/pgx/v5 v5.6.0
    github.com/segmentio/kafka-go v0.4.47
)
```

| 의존성 | 선택 이유 |
|--------|----------|
| `pgx/v5` | PostgreSQL 공식 Go 드라이버. database/sql 없이 직접 사용해 오버헤드 최소화. |
| `kafka-go` | 순수 Go 구현. CGo 없음. `segmentio/kafka-go`는 librdkafka 없이 동작해 Docker 빌드가 단순하다. |

---

## 9. Dockerfile

```dockerfile
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /market-gateway ./...

FROM alpine:3.20
COPY --from=build /market-gateway /market-gateway
ENTRYPOINT ["/market-gateway"]
```

`CGO_ENABLED=0` + `alpine` 베이스로 최종 이미지 크기 ~10MB. librdkafka 의존 없는 `kafka-go` 덕분에 가능.

---

## 10. KIS WebSocket 전환 경로

현재 `tickLoop` 의 `nextPrice()` 호출 부분을 교체하면 실거래소 연동이 가능하다:

```go
// 현재
price = nextPrice(price)

// KIS 연동 후 (의사코드)
msg := <-kisChannel  // KIS WebSocket 수신 채널
price = parsePrice(msg)
```

goroutine-per-stock 구조는 KIS H0STASP0 호가 구독(종목별 별도 구독)과 자연스럽게 맞아떨어진다.

---

## 11. 알려진 한계

| 항목 | 현황 | 향후 개선 |
|------|------|----------|
| 종목 목록 동적 갱신 | 시작 시 1회 로드만. 신규 종목 추가 시 재시작 필요 | Kafka 토픽 또는 DB polling으로 동적 반영 |
| 메시지 직렬화 형식 | JSON (스키마 없음) | Avro + Schema Registry 도입 |
| 발행 실패 처리 | `WriteMessages` 오류 무시 | 재시도 큐 또는 DLQ 추가 |
| 모니터링 | 없음 | Prometheus 메트릭 (`/metrics`), 토픽 랙 경보 |
