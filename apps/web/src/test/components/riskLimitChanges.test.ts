import { describe, expect, it } from "vitest";
import { cancelPayload, describePending, fmtKst, limitsPayload, pendingFor } from "@/components/risk/limitChanges";
import type { RiskLimits } from "@/components/risk/useRiskExposure";

const server: RiskLimits = {
  dailyLossLimitPct: 3,
  concentrationLimitPct: 30,
  varLimitPct: 5,
  maxPositionCount: 10,
  maxHourlyOrders: 5,
  sectorConcentrationLimitPct: 40,
  isActive: true,
  pendingChanges: [],
  coolingOffHours: 24,
};

describe("limitsPayload", () => {
  it("바뀐 항목만 보낸다 — 그대로 보낸 항목은 서버가 대기 중인 완화를 취소하기 때문", () => {
    expect(limitsPayload(server, { ...server, varLimitPct: 4 })).toEqual({ varLimitPct: 4 });
    expect(limitsPayload(server, server)).toEqual({});
  });

  it("섹터 한도를 비우면 해제 플래그로 보낸다", () => {
    expect(limitsPayload(server, { ...server, sectorConcentrationLimitPct: null })).toEqual({ clearSectorConcentrationLimit: true });
    expect(limitsPayload({ ...server, sectorConcentrationLimitPct: null }, { ...server, sectorConcentrationLimitPct: 25 }))
      .toEqual({ sectorConcentrationLimitPct: 25 });
  });

  it("리스크 체크 토글을 보낸다", () => {
    expect(limitsPayload(server, { ...server, isActive: false })).toEqual({ isActive: false });
  });
});

describe("cancelPayload", () => {
  it("현재 값을 그대로 보내 대기 변경을 취소한다", () => {
    expect(cancelPayload(server, "varLimitPct")).toEqual({ varLimitPct: 5 });
    expect(cancelPayload(server, "isActive")).toEqual({ isActive: true });
    expect(cancelPayload(server, "sectorConcentrationLimitPct")).toEqual({ sectorConcentrationLimitPct: 40 });
    expect(cancelPayload({ ...server, sectorConcentrationLimitPct: null }, "sectorConcentrationLimitPct"))
      .toEqual({ clearSectorConcentrationLimit: true });
    expect(cancelPayload(server, "unknown")).toBeNull();
  });
});

describe("대기 변경 표시", () => {
  const p = (field: string, value: number | null) => ({ field, value, requestedAt: "2026-10-05T05:00:00Z", effectiveAt: "2026-10-06T05:00:00Z" });

  it("현재 값 → 대기 값", () => {
    expect(describePending(server, p("varLimitPct", 8))).toBe("1일 VaR 한도 5% → 8%");
    expect(describePending(server, p("isActive", 0))).toBe("리스크 체크 켬 → 끔");
    expect(describePending(server, p("sectorConcentrationLimitPct", null))).toBe("섹터 최대 비중 40% → 미설정");
  });

  it("이번 요청으로 대기에 들어간 항목만 고른다", () => {
    const pending = [p("varLimitPct", 8), p("sectorConcentrationLimitPct", null), p("isActive", 0)];
    expect(pendingFor({ clearSectorConcentrationLimit: true, varLimitPct: 8 }, pending).map((x) => x.field))
      .toEqual(["varLimitPct", "sectorConcentrationLimitPct"]);
  });

  it("적용 시각은 KST", () => {
    expect(fmtKst("2026-10-06T05:00:00Z")).toBe("10.06 14:00");
  });
});
