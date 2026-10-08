import { describe, it, expect } from "vitest";
import type { WatchRuleResponse } from "@monticker/types";
import { orderTypeLabel, sizeLabel, targetLabel } from "@/components/watchrule/WatchRuleRow";

const base: WatchRuleResponse = {
  id: 1, stockId: 19, eventType: "VOLUME_SURGE", side: "BUY", quantity: 10,
  minImportanceScore: 0, cooldownSec: 600, isActive: true, createdAt: "2026-10-08T00:00:00Z",
};
const label = (id: number) => `종목 #${id}`;

// ADR-095 — 규칙 카드의 대상·주문 유형·수량 한 줄
describe("WatchRuleRow labels", () => {
  it("기존 규칙은 종목 · 시장가 · 주 수", () => {
    expect(targetLabel(base, label)).toBe("종목 #19");
    expect(orderTypeLabel(base)).toBe("시장가");
    expect(sizeLabel(base)).toBe("10주");
  });

  it("그룹 · 지정가 오프셋 · 계좌 %", () => {
    const r: WatchRuleResponse = {
      ...base, stockId: null, quantity: null, targetType: "GROUP", targetGroupId: 4, targetGroupName: "반도체",
      orderType: "LIMIT", limitOffsetBps: -50, sizeType: "EQUITY_PCT", equityPct: 5,
    };
    expect(targetLabel(r, label)).toBe("그룹 · 반도체");
    expect(orderTypeLabel(r)).toBe("지정가 (가격 −0.50%)");
    expect(orderTypeLabel({ ...r, limitOffsetBps: 120 })).toBe("지정가 (가격 +1.20%)");
    expect(sizeLabel(r)).toBe("자산 5%");
  });

  it("지워진 그룹은 삭제된 그룹으로 보인다", () => {
    expect(targetLabel({ ...base, stockId: null, targetType: "GROUP", targetGroupId: 4, targetGroupMissing: true }, label))
      .toBe("삭제된 관심종목 그룹");
  });
});
