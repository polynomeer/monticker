# monticker — Product

> Read this when: deciding what to build, scoping a feature, or checking MVP boundaries.

## Product Identity

monticker is an **event-centric stock observation app**.

It is not a price viewer. The core value is:

> Show *why* a price moved — by overlaying news, disclosures, volume anomalies, and sentiment signals directly onto the chart timeline.

**Conventional app:**
```
Samsung 70,000 KRW  +2.1%  [chart]  [news list]
```

**monticker:**
```
Samsung 10:24 spike
  Volume: 4.8× above 5-min average
  News: "HBM supply expansion expected"
  Sentiment: Anticipation / Sector momentum
  Past pattern: 63% of similar spikes showed volatility within 1h
```

---

## Core Feature Axes

```
monticker
├── 1. Real-time price monitoring
├── 2. Event timeline overlaid on chart
├── 3. News / disclosure / sentiment keyword mapping
├── 4. Anomaly detection (price spike, volume surge)
├── 5. Watchlist & portfolio observation
└── 6. Review-oriented paper trading
```

---

## Key Screens

### Home

Purpose: show what is moving abnormally in today's market.

```
Home
├── Market summary (KOSPI / KOSDAQ / NASDAQ / S&P500, sector moves)
├── Movers
│   ├── 5-min spike
│   ├── Volume surge
│   ├── News-accompanied surge
│   └── Disclosure-accompanied surge
├── Watchlist alerts
│   ├── Price breakout
│   ├── Volume anomaly
│   ├── News event
│   └── Sentiment change
└── Daily learning card
```

### Stock Detail

The most important screen in monticker.

```
Stock Detail
├── Current price, change rate, volume, market cap, daily volatility
├── Real-time chart
│   ├── 1m / 5m / 1d candles
│   └── Event markers: news, disclosure, volume surge, sentiment
├── Movement interpretation panel
│   ├── "Why might this have moved?"
│   ├── Related news & disclosures
│   └── Sector context
├── Investor sentiment panel
│   └── Anticipation / Fear / Overheating / Disappointment / Neutral
└── Paper trade / review / memo
```

### Chart Event Timeline

monticker's primary differentiator.

```
Price chart
├── 09:12  Volume surge
├── 09:18  News published
├── 09:21  Price +3%
├── 09:30  Sector-wide rise
├── 10:05  Disclosure published
└── 10:40  Overheating warning
```

---

## Navigation Structure

```
Navigation
├── Home
├── Watchlist
├── Explore
├── Portfolio
├── Paper Trade
├── Alerts
└── My Page
```

---

## MVP Scope

### Include

```
├── Sign-up / login
├── Stock search
├── Watchlist
├── Current price
├── 1m / 1d chart
├── News collection + stock mapping
├── Volume surge event detection
├── Event markers on chart
└── Basic alerts
```

The single most important MVP screen:

> **Stock detail = chart + event timeline + related news**

### Exclude from MVP

```
Real order execution
Complex portfolio analytics
AI auto buy/sell
Social community
Live streaming
Advanced backtesting
Native mobile app
```

---

## Development Phases

| Phase | Focus |
|-------|-------|
| 1 | Foundation: auth, stock master, watchlist, price API, basic chart |
| 2 | Core: news collection, stock mapping, volume surge, stock_events, timeline |
| 3 | Realtime: WebSocket, Redis cache, live price, alert engine |
| 4 | AI Insight: news/disclosure summary, sentiment keywords, event scoring |
| 5 | Paper trading: virtual trades, snapshot, review cards, pattern analysis |

---

## Key Design Decision

The central domain object is **`stock_events`**, not `price`.

```
Conventional app:  stock → price → chart
monticker:         stock → price + news + disclosure + volume + sentiment
                       → stock_events → chart timeline
```

All data sources must flow into `stock_events`. Everything else is downstream.
