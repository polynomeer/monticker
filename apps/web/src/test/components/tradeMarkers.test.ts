import { describe, expect, it } from "vitest";
import {
  aggregateTradeMarkers,
  bucketOf,
  describeTradeGroup,
  kstDateToEpoch,
  tradeMarkPoints,
  tradeTimesKst,
} from "@/components/stock/chart/tradeMarkers";
import type { TradeMarker } from "@/components/stock/chart/types";

const at = (iso: string) => Math.floor(Date.parse(iso) / 1000);
const buy = (time: number, qty = 1, price = 100): TradeMarker => ({ time, side: "BUY", qty, price });
const sell = (time: number, qty = 1, price = 100): TradeMarker => ({ time, side: "SELL", qty, price });

describe("bucketOf — Asia/Seoul 경계", () => {
  it("일봉은 KST 자정에서 끊는다 (UTC 15:00)", () => {
    // 2026-10-05 00:00 KST = 2026-10-04T15:00Z
    expect(bucketOf(at("2026-10-04T14:59:59Z"), "1d")).toBe(bucketOf(at("2026-10-04T00:00:00+09:00"), "1d"));
    expect(bucketOf(at("2026-10-04T15:00:00Z"), "1d")).toBe(bucketOf(at("2026-10-05T00:00:00+09:00"), "1d"));
    expect(bucketOf(at("2026-10-04T15:00:00Z"), "1d")).not.toBe(bucketOf(at("2026-10-04T14:59:59Z"), "1d"));
  });

  it("UTC 자정 직전·직후는 같은 KST 날짜다", () => {
    // UTC 날짜는 바뀌지만 KST로는 둘 다 10-05 오전
    expect(bucketOf(at("2026-10-04T23:59:59Z"), "1d")).toBe(bucketOf(at("2026-10-05T00:00:00Z"), "1d"));
  });

  it("분·시간봉은 KST 정각/정분에서 끊는다", () => {
    expect(bucketOf(at("2026-10-05T09:59:59+09:00"), "1h")).toBe(bucketOf(at("2026-10-05T09:00:00+09:00"), "1h"));
    expect(bucketOf(at("2026-10-05T10:00:00+09:00"), "1h")).not.toBe(bucketOf(at("2026-10-05T09:59:59+09:00"), "1h"));
    expect(bucketOf(at("2026-10-05T09:14:59+09:00"), "15m")).toBe(bucketOf(at("2026-10-05T09:00:00+09:00"), "15m"));
    expect(bucketOf(at("2026-10-05T09:15:00+09:00"), "15m")).not.toBe(bucketOf(at("2026-10-05T09:14:59+09:00"), "15m"));
    expect(bucketOf(at("2026-10-05T09:02:59+09:00"), "3m")).toBe(bucketOf(at("2026-10-05T09:00:00+09:00"), "3m"));
    expect(bucketOf(at("2026-10-05T09:03:00+09:00"), "3m")).not.toBe(bucketOf(at("2026-10-05T09:02:59+09:00"), "3m"));
    expect(bucketOf(at("2026-10-05T09:00:59+09:00"), "1m")).toBe(bucketOf(at("2026-10-05T09:00:00+09:00"), "1m"));
  });
});

describe("aggregateTradeMarkers", () => {
  // 일봉 3개 — KST 자정 스탬프
  const daysKstMidnight = ["2026-10-05", "2026-10-06", "2026-10-07"].map((d) => ({ time: kstDateToEpoch(d) }));
  // 같은 날짜를 UTC 자정(KST 09:00)으로 스탬프한 일봉
  const daysUtcMidnight = ["2026-10-05", "2026-10-06", "2026-10-07"].map((d) => ({ time: at(`${d}T00:00:00Z`) }));

  it("체결을 그 KST 날짜의 일봉에 붙인다 — 스탬프 규칙과 무관", () => {
    // 10-06 00:30 KST = 10-05T15:30Z (UTC로는 아직 10-05)
    const t = at("2026-10-05T15:30:00Z");
    expect(aggregateTradeMarkers(daysKstMidnight, [buy(t)], "1d")[0].index).toBe(1);
    expect(aggregateTradeMarkers(daysUtcMidnight, [buy(t)], "1d")[0].index).toBe(1);
    // 10-05 23:59 KST
    const t2 = at("2026-10-05T14:59:00Z");
    expect(aggregateTradeMarkers(daysKstMidnight, [buy(t2)], "1d")[0].index).toBe(0);
  });

  it("같은 봉·같은 방향 체결은 건수·수량합·가중평균가로 묶는다", () => {
    const d = kstDateToEpoch("2026-10-06");
    const groups = aggregateTradeMarkers(daysKstMidnight, [
      buy(d + 3600 * 10, 10, 100),
      buy(d + 3600 * 11, 30, 200),
      sell(d + 3600 * 12, 5, 210),
    ], "1d");
    expect(groups).toHaveLength(2);
    const [b, s] = groups;
    expect(b).toMatchObject({ index: 1, side: "BUY", count: 2, quantity: 40 });
    expect(b.avgPrice).toBeCloseTo(175);
    expect(s).toMatchObject({ index: 1, side: "SELL", count: 1, quantity: 5, avgPrice: 210 });
  });

  it("봉이 없는 버킷의 체결은 직전 봉에, 구간 밖 체결은 버린다", () => {
    const candles = [daysKstMidnight[0], daysKstMidnight[2]]; // 10-06 봉 없음
    const g = aggregateTradeMarkers(candles, [
      buy(kstDateToEpoch("2026-10-06") + 36_000),
      buy(kstDateToEpoch("2026-10-04") + 36_000), // 첫 봉보다 이름
      buy(kstDateToEpoch("2026-10-08") + 36_000), // 마지막 봉보다 늦음
    ], "1d");
    expect(g).toHaveLength(1);
    expect(g[0].index).toBe(0);
  });

  it("수량·가격이 유효하지 않은 체결은 버린다", () => {
    const t = kstDateToEpoch("2026-10-05") + 36_000;
    const g = aggregateTradeMarkers(daysKstMidnight, [
      buy(t, 0), buy(t, -1), buy(t, 1, NaN), buy(t, 1, 0), { time: NaN, side: "BUY", qty: 1, price: 1 },
    ], "1d");
    expect(g).toEqual([]);
  });

  it("15분봉 경계(09:14:59 / 09:15:00 KST)를 지킨다", () => {
    const candles = ["09:00", "09:15", "09:30"].map((hm) => ({ time: at(`2026-10-05T${hm}:00+09:00`) }));
    const g = aggregateTradeMarkers(candles, [
      buy(at("2026-10-05T09:14:59+09:00")),
      buy(at("2026-10-05T09:15:00+09:00")),
    ], "15m");
    expect(g.map((x) => x.index)).toEqual([0, 1]);
  });

  it("체결이 많아도 봉·방향당 마커 하나", () => {
    const candles = Array.from({ length: 10 }, (_, i) => ({ time: at("2026-10-05T09:00:00+09:00") + i * 60 }));
    const trades = Array.from({ length: 1000 }, (_, i) =>
      (i % 2 ? sell : buy)(candles[i % 10].time + (i % 59), 1, 100 + (i % 7)));
    const g = aggregateTradeMarkers(candles, trades, "1m");
    expect(g.length).toBeLessThanOrEqual(20);
    expect(g.reduce((a, x) => a + x.count, 0)).toBe(1000);
  });
});

describe("표시 문구", () => {
  it("건수·수량·평균가를 요약하고 체결 시각은 KST로 쓴다", () => {
    const candles = [{ time: kstDateToEpoch("2026-10-05") }];
    const [g] = aggregateTradeMarkers(candles, [
      buy(at("2026-10-05T00:30:00Z"), 10, 71_000), // 09:30 KST
      buy(at("2026-10-05T01:00:00Z"), 10, 71_400), // 10:00 KST
    ], "1d");
    expect(describeTradeGroup(g)).toBe("매수 2건 · 20주 · 평균 71,200원");
    const times = tradeTimesKst(g);
    expect(times).toContain("09:30");
    expect(times).toContain("10:00");
  });

  it("1건이면 라벨을 덧붙인다", () => {
    const candles = [{ time: kstDateToEpoch("2026-10-05") }];
    const [g] = aggregateTradeMarkers(candles, [{ ...sell(kstDateToEpoch("2026-10-05"), 3, 50_000), label: "익절" }], "1d");
    expect(describeTradeGroup(g)).toBe("매도 · 3주 · 50,000원 · 익절");
  });
});

describe("tradeMarkPoints — 어댑터 매핑", () => {
  const theme = { upColor: "#0ecb81", downColor: "#f6465d" };
  const candles = [
    { time: kstDateToEpoch("2026-10-05"), open: 10, high: 12, low: 9, close: 11 },
    { time: kstDateToEpoch("2026-10-06"), open: 11, high: 13, low: 10, close: 12 },
  ];

  it("매수는 봉 아래 상승색, 매도는 봉 위 하락색 — x는 봉 인덱스", () => {
    const groups = aggregateTradeMarkers(candles, [
      buy(candles[1].time + 100), buy(candles[1].time + 200), sell(candles[0].time + 100),
    ], "1d");
    const pts = tradeMarkPoints(groups, candles, theme);
    expect(pts).toHaveLength(2);
    const [s, b] = pts;
    expect(s).toMatchObject({ coord: [0, 12], value: "S", symbolRotate: 0, itemStyle: { color: "#f6465d" } });
    expect(b).toMatchObject({ coord: [1, 10], value: "B2", symbolRotate: 180, itemStyle: { color: "#0ecb81" } });
    expect(b.symbolOffset[1]).toBeGreaterThan(0);
    expect(s.symbolOffset[1]).toBeLessThan(0);
  });

  it("테마 색을 그대로 따른다(하드코딩 없음)", () => {
    const groups = aggregateTradeMarkers(candles, [buy(candles[0].time)], "1d");
    const [p] = tradeMarkPoints(groups, candles, { upColor: "#2563eb", downColor: "#f97316" });
    expect(p.itemStyle.color).toBe("#2563eb");
  });
});
