import { describe, expect, it } from "vitest";
import { cumulativeDepth } from "@/components/stock/chart/depth";

describe("cumulativeDepth", () => {
  it("accumulates bids from the best (highest) price down and asks from the best (lowest) up", () => {
    const d = cumulativeDepth(
      [{ price: 99, quantity: 10 }, { price: 100, quantity: 5 }, { price: 98, quantity: 1 }],
      [{ price: 102, quantity: 7 }, { price: 101, quantity: 3 }],
    );
    // 가격 오름차순: 98(16) 99(15) 100(5)
    expect(d.bids).toEqual([[98, 16], [99, 15], [100, 5]]);
    expect(d.asks).toEqual([[101, 3], [102, 10]]);
    expect(d.bidTotal).toBe(16);
    expect(d.askTotal).toBe(10);
    expect(d.bestBid).toBe(100);
    expect(d.bestAsk).toBe(101);
  });

  it("drops empty or invalid levels and handles a one-sided book", () => {
    const d = cumulativeDepth([], [{ price: 101, quantity: 0 }, { price: NaN, quantity: 3 }, { price: 103, quantity: 2 }]);
    expect(d.bids).toEqual([]);
    expect(d.asks).toEqual([[103, 2]]);
    expect(d.bestBid).toBeNull();
    expect(d.bidTotal).toBe(0);
  });

  it("does not mutate the input", () => {
    const bids = [{ price: 1, quantity: 1 }, { price: 2, quantity: 1 }];
    cumulativeDepth(bids, []);
    expect(bids.map((b) => b.price)).toEqual([1, 2]);
  });
});
