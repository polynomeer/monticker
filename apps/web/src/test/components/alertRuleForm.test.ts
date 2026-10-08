import { describe, expect, it } from "vitest";
import { buildRuleRequest, createRuleErrorMessage, defaultDraft } from "@/components/alerts/ruleForm";

describe("buildRuleRequest", () => {
  it("requires a stock for every rule (server rejects stockId=null)", () => {
    const { errors, body } = buildRuleRequest({ ...defaultDraft("VOLUME_SURGE"), stockId: null });
    expect(errors.stockId).toBeTruthy();
    expect(body).toBeNull();
  });

  it("builds price rules with a positive threshold only", () => {
    expect(buildRuleRequest({ ...defaultDraft("PRICE_ABOVE", 7), threshold: "71000" }).body)
      .toEqual({ stockId: 7, ruleType: "PRICE_ABOVE", condition: { threshold: 71000 } });
    expect(buildRuleRequest({ ...defaultDraft("PRICE_BELOW", 7), threshold: "" }).errors.threshold).toBeTruthy();
    expect(buildRuleRequest({ ...defaultDraft("PRICE_BELOW", 7), threshold: "0" }).errors.threshold).toBeTruthy();
  });

  it("builds RSI rules with default period and bounds the threshold", () => {
    expect(buildRuleRequest(defaultDraft("RSI_BELOW", 3)).body)
      .toEqual({ stockId: 3, ruleType: "RSI_BELOW", condition: { threshold: 30, period: 14 } });
    expect(buildRuleRequest({ ...defaultDraft("RSI_ABOVE", 3), threshold: "120" }).errors.threshold).toBeTruthy();
    expect(buildRuleRequest({ ...defaultDraft("RSI_ABOVE", 3), period: "1.5" }).errors.period).toBeTruthy();
  });

  it("moving-average and volume rules need no threshold", () => {
    expect(buildRuleRequest(defaultDraft("PRICE_ABOVE_MA", 1)).body?.condition).toEqual({ period: 20 });
    expect(buildRuleRequest(defaultDraft("VOLUME_SURGE", 1)).body?.condition).toEqual({});
  });

  it("holding drop needs a percentage in (0, 100]", () => {
    expect(buildRuleRequest(defaultDraft("HOLDING_DROP", 1)).body?.condition).toEqual({ dropPct: 10 });
    expect(buildRuleRequest({ ...defaultDraft("HOLDING_DROP", 1), dropPct: "-5" }).errors.dropPct).toBeTruthy();
  });
});

describe("createRuleErrorMessage", () => {
  it("explains rate limit and validation failures", () => {
    expect(createRuleErrorMessage(429)).toContain("20개");
    expect(createRuleErrorMessage(400)).toContain("입력값");
    expect(createRuleErrorMessage(500, "서버 오류")).toBe("서버 오류");
  });
});
