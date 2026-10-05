import { describe, expect, it } from "vitest";
import { fmtChangeRate, isOnKrxTick, krxBand, krxTick } from "@/lib/krxOrderPrice";

describe("krxOrderPrice", () => {
  it("uses the 2023 KRX tick table", () => {
    expect(krxTick(1_999)).toBe(1);
    expect(krxTick(2_000)).toBe(5);
    expect(krxTick(19_990)).toBe(10);
    expect(krxTick(20_000)).toBe(50);
    expect(krxTick(70_000)).toBe(100);
    expect(krxTick(500_000)).toBe(1_000);
  });

  it("accepts only positive whole-won prices on the tick", () => {
    expect(isOnKrxTick(70_000)).toBe(true);
    expect(isOnKrxTick(70_050)).toBe(false);
    expect(isOnKrxTick(70_000.5)).toBe(false);
    expect(isOnKrxTick(0)).toBe(false);
  });

  it("computes the ±30% band from the previous close", () => {
    expect(krxBand(70_000)).toEqual({ low: 49_000, high: 91_000 });
  });

  it("formats the change rate with a sign, or null when unknown", () => {
    expect(fmtChangeRate(1.256)).toBe("+1.26%");
    expect(fmtChangeRate(-0.4)).toBe("−0.40%");
    expect(fmtChangeRate(0)).toBe("0.00%");
    expect(fmtChangeRate(null)).toBeNull();
  });
});
