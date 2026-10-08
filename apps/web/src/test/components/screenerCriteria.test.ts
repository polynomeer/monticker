import { describe, expect, it } from "vitest";
import {
  CHANGE_STOPS, DEFAULT_CRITERIA, marketGroup, changeRangeLabel, changeToStops, fromServer, sameCriteria, stopsToChange, toQuery, volMultToStop,
} from "@/components/screener/criteria";

describe("screener criteria", () => {
  it("maps slider ends to unbounded and back", () => {
    const last = CHANGE_STOPS.length - 1;
    expect(stopsToChange(0, last)).toEqual({ minChange: null, maxChange: null });
    expect(changeToStops(null, null)).toEqual([0, last]);
    const { minChange, maxChange } = stopsToChange(6, 9); // +1% ~ +10%
    expect([minChange, maxChange]).toEqual([1, 10]);
    expect(changeToStops(1, 10)).toEqual([6, 9]);
  });

  it("orders crossed thumbs and keeps max not below min", () => {
    expect(stopsToChange(9, 6)).toEqual({ minChange: 1, maxChange: 10 });
    const [a, b] = changeToStops(5, 1);
    expect(b).toBeGreaterThanOrEqual(a);
  });

  it("labels ranges", () => {
    expect(changeRangeLabel(null, null)).toBe("전체");
    expect(changeRangeLabel(1, null)).toBe("+1% ~");
    expect(changeRangeLabel(-5, 3)).toBe("-5% ~ +3%");
  });

  it("volume multiple stops", () => {
    expect(volMultToStop(null)).toBe(0);
    expect(volMultToStop(2)).toBe(3);
    expect(volMultToStop(100)).toBe(6);
  });

  it("builds a query with only the set filters", () => {
    const q = new URLSearchParams(toQuery("realtime", { ...DEFAULT_CRITERIA, sectors: ["반도체", "금융"], minChange: 1, events: ["NEWS", "QUANT_SIGNAL"] }, 20, 40));
    expect(q.get("sectors")).toBe("반도체,금융");
    expect(q.get("minChange")).toBe("1");
    expect(q.has("maxChange")).toBe(false);
    expect(q.get("events")).toBe("NEWS,QUANT_SIGNAL");
    expect(q.get("offset")).toBe("40");
  });

  it("compares criteria regardless of list order and fills server defaults", () => {
    const a = { ...DEFAULT_CRITERIA, sectors: ["a", "b"] };
    const b = { ...DEFAULT_CRITERIA, sectors: ["b", "a"] };
    expect(sameCriteria(a, b)).toBe(true);
    expect(fromServer({ market: "domestic" })).toEqual({ ...DEFAULT_CRITERIA, market: "domestic" });
  });

  it("groups kospi and kosdaq under the domestic segment", () => {
    expect(marketGroup("kospi")).toBe("domestic");
    expect(marketGroup("kosdaq")).toBe("domestic");
    expect(marketGroup("domestic")).toBe("domestic");
    expect(marketGroup("overseas")).toBe("overseas");
    expect(marketGroup("all")).toBe("all");
    expect(new URLSearchParams(toQuery("realtime", { ...DEFAULT_CRITERIA, market: "kosdaq" }, 50, 0)).get("market")).toBe("kosdaq");
  });
});
