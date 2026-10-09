import { describe, expect, it } from "vitest";
import { ruleValue } from "@/components/stock/OrderForm";

/** 주문 전 리스크 체크의 일간 손실 표시 — 서버는 손익(이익 +, 손실 −)과 음수 한도를 보낸다. */
describe("ruleValue · DailyLossRule", () => {
  const rule = (current: number, limit: number) => ({ rule: "DailyLossRule", passed: true, detail: "", current, limit });

  it("shows the loss and the limit as positive amounts", () => {
    expect(ruleValue(rule(-12_000, -298_786))).toBe("손실 12,000 / 298,786원");
  });

  it("shows zero loss on a profitable or flat day instead of a negative limit", () => {
    expect(ruleValue(rule(0, -298_786))).toBe("손실 0 / 298,786원");
    expect(ruleValue(rule(5_000, -298_786))).toBe("손실 0 / 298,786원");
  });
});
