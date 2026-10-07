import { describe, expect, it } from "vitest";
import { mergeLevels } from "@/components/stock/OrderBook";

describe("mergeLevels", () => {
  it("합치기: 같은 가격 레벨은 잔량·금액을 더해 하나로 만든다", () => {
    const merged = mergeLevels([
      { price: 71200, quantity: 10, amount: 712000 },
      { price: 71200, quantity: 5, amount: 356000 },
      { price: 71300, quantity: 1, amount: 71300 },
    ]);
    expect(merged).toEqual([
      { price: 71200, quantity: 15, amount: 1068000 },
      { price: 71300, quantity: 1, amount: 71300 },
    ]);
  });

  it("중복이 없으면 그대로 둔다", () => {
    const levels = [{ price: 1, quantity: 1, amount: 1 }];
    expect(mergeLevels(levels)).toEqual(levels);
  });
});
