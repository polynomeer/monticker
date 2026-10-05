import { beforeEach, describe, expect, it } from "vitest";
import { saveRebalanceDraft, takeRebalanceDraft, toDraftWeights, type RebalanceDraft } from "@/lib/rebalanceDraft";

const draft = (over: Partial<RebalanceDraft> = {}): RebalanceDraft => ({
  createdAt: Date.now(),
  rows: [
    { symbol: "005930", name: "삼성전자", stockId: 2, weightPct: 60 },
    { symbol: "000660", name: "SK하이닉스", stockId: 3, weightPct: 40 },
  ],
  expectedReturn: 0.1,
  expectedRisk: 0.2,
  suggestion: "",
  ...over,
});

describe("toDraftWeights", () => {
  it("반올림 합이 100을 넘으면 가장 큰 비중에서 덜어 낸다", () => {
    const out = toDraftWeights([
      { stockId: 1, weight: 0.33349 },
      { stockId: 2, weight: 0.33349 },
      { stockId: 3, weight: 0.33302 },
    ]);
    const total = out.reduce((a, w) => a + w.weightPct, 0);
    expect(total).toBeLessThanOrEqual(100);
    expect(out.every((w) => w.weightPct > 0)).toBe(true);
  });

  it("0%로 반올림되는 종목은 뺀다 — 서버가 0 이하 비중을 거부한다", () => {
    const out = toDraftWeights([
      { stockId: 1, weight: 0.9996 },
      { stockId: 2, weight: 0.0004 },
    ]);
    expect(out.map((w) => w.stockId)).toEqual([1]);
  });
});

describe("rebalance draft handoff", () => {
  beforeEach(() => sessionStorage.clear());

  it("한 번 읽으면 사라진다", () => {
    expect(saveRebalanceDraft(draft())).toBe(true);
    expect(takeRebalanceDraft()?.rows).toHaveLength(2);
    expect(takeRebalanceDraft()).toBeNull();
  });

  it("30분이 지난 초안은 버린다", () => {
    saveRebalanceDraft(draft({ createdAt: Date.now() - 31 * 60_000 }));
    expect(takeRebalanceDraft()).toBeNull();
  });

  it("합이 100%를 넘거나 비중이 0 이하인 초안은 저장도 복원도 하지 않는다", () => {
    expect(saveRebalanceDraft(draft({ rows: [{ symbol: "A", name: "A", stockId: 1, weightPct: 70 }, { symbol: "B", name: "B", stockId: 2, weightPct: 40 }] }))).toBe(false);
    sessionStorage.setItem("monticker.rebalanceDraft.v1", JSON.stringify(draft({ rows: [{ symbol: "A", name: "A", stockId: 1, weightPct: 0 }] })));
    expect(takeRebalanceDraft()).toBeNull();
  });

  it("깨진 값은 무시한다", () => {
    sessionStorage.setItem("monticker.rebalanceDraft.v1", "{not json");
    expect(takeRebalanceDraft()).toBeNull();
  });
});
