import { describe, expect, it } from "vitest";
import type { BrokerageBalanceResponse } from "@monticker/types";
import { realOrderRiskRows, type RealRiskLimits } from "@/lib/realOrderRisk";

const limits: RealRiskLimits = {
  concentrationLimitPct: 30, dailyLossLimitPct: 3, varLimitPct: 5, maxPositionCount: 10, maxHourlyOrders: 20, sectorConcentrationLimitPct: null,
};
const balance: BrokerageBalanceResponse = {
  cash: 5_000_000, totalEvaluated: 10_000_000,
  // 한 종목이 여러 행(현금·신용)으로 오면 합친다. 수량 0 행은 뺀다 — 서버 스냅샷과 같다.
  holdings: [
    { symbol: "005930", quantity: 20, avgPrice: 60000, currentPrice: 70000 },
    { symbol: "005930", quantity: 10, avgPrice: 60000, currentPrice: 70000 },
    { symbol: "000660", quantity: 0, avgPrice: 1, currentPrice: 1 },
  ],
};
const conc = (rows: ReturnType<typeof realOrderRiskRows>) => rows.find((r) => r.rule === "ConcentrationRule");

describe("realOrderRiskRows — 실전 주문 전 리스크 행", () => {
  it("집중도는 증권사 총평가액 대비 (보유 합계 + 주문) × 가격으로 판정한다", () => {
    // (30 + 10) × 70,000 / 10,000,000 = 28%
    const ok = conc(realOrderRiskRows({ side: "BUY", quantity: 10, price: 70000, symbol: "005930", balance, limits }));
    expect(ok?.state).toBe("ok");
    expect(ok?.label).toBe("종목 집중도 28% / 한도 30%");

    // (30 + 20) × 70,000 / 10,000,000 = 35%
    const fail = conc(realOrderRiskRows({ side: "BUY", quantity: 20, price: 70000, symbol: "005930", balance, limits }));
    expect(fail?.state).toBe("fail");
    expect(fail?.reason).toContain("35%");
  });

  it("총평가액을 모르면 서버처럼 판정 불가로 막는다", () => {
    const r = conc(realOrderRiskRows({ side: "BUY", quantity: 1, price: 70000, symbol: "005930", balance: { ...balance, totalEvaluated: 0 }, limits }));
    expect(r?.state).toBe("fail");
  });

  it("가격을 모르면 화면이 판정하지 않고 서버에 맡긴다", () => {
    expect(conc(realOrderRiskRows({ side: "BUY", quantity: 1, price: 0, symbol: "005930", balance, limits }))?.state).toBe("server");
  });

  it("한도·잔고를 아직 못 읽었으면 대기(막지 않음)", () => {
    const rows = realOrderRiskRows({ side: "BUY", quantity: 1, price: 70000, symbol: "005930", balance: undefined, limits: undefined });
    expect(rows.map((r) => r.state)).toEqual(["wait"]);
  });

  it("매도에는 집중도를 걸지 않고, 서버만 아는 규칙은 막지 않는 '서버 검증' 행이다", () => {
    const rows = realOrderRiskRows({ side: "SELL", quantity: 999, price: 70000, symbol: "005930", balance, limits });
    expect(conc(rows)).toBeUndefined();
    expect(rows.every((r) => r.state === "server")).toBe(true);
  });
});
