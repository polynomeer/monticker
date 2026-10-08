import { describe, expect, it } from "vitest";
import type { WatchRuleResponse } from "@monticker/types";
import {
  MAX_EQUITY_PCT,
  MAX_OFFSET_BPS,
  MAX_QUANTITY,
  MIN_EQUITY_PCT,
  validateWatchRuleForm,
  type WatchRuleFormInput,
} from "@/components/watchrule/WatchRuleForm";
import { applyOptimisticPatch, buildWatchRulePatch, isOptimisticSafe } from "@/components/watchrule/watchRulePatch";

const base: WatchRuleFormInput = {
  target: "STOCK", stockId: 19, groupId: null, eventType: "VOLUME_SURGE", side: "BUY",
  orderType: "MARKET", offsetBps: "", sizeType: "SHARES", quantity: "10", equityPct: "",
  minImportance: "0", cooldownSec: 600, name: "", ruleSetId: "", signalDirection: "BUY",
  required: [], windowSec: 1800, dailyLimit: "",
};

const errorOf = (patch: Partial<WatchRuleFormInput>, opts?: { checkStrategy?: boolean }) => {
  const r = validateWatchRuleForm({ ...base, ...patch }, opts);
  return "error" in r ? r.error : null;
};

// 서버 범위(WatchRuleService·WatchRuleSizing·V92 CHECK)와 같은 경계 — 생성·수정이 같은 함수를 쓴다
describe("validateWatchRuleForm — 서버 범위와 같은 경계", () => {
  it.each([
    ["수량 1", { quantity: "1" }, null],
    ["수량 상한", { quantity: String(MAX_QUANTITY) }, null],
    ["수량 0", { quantity: "0" }, "1 이상"],
    ["수량 상한 + 1", { quantity: String(MAX_QUANTITY + 1) }, "넘을 수 없습니다"],
    ["수량 소수", { quantity: "1.5" }, "1 이상"],
    ["수량 빈칸", { quantity: "" }, "1 이상"],
    ["오프셋 +1000", { orderType: "LIMIT" as const, offsetBps: String(MAX_OFFSET_BPS) }, null],
    ["오프셋 −1000", { orderType: "LIMIT" as const, offsetBps: String(-MAX_OFFSET_BPS) }, null],
    ["오프셋 0", { orderType: "LIMIT" as const, offsetBps: "0" }, null],
    ["오프셋 +1001", { orderType: "LIMIT" as const, offsetBps: "1001" }, "1000bp"],
    ["오프셋 −1001", { orderType: "LIMIT" as const, offsetBps: "-1001" }, "1000bp"],
    ["오프셋 소수", { orderType: "LIMIT" as const, offsetBps: "12.5" }, "1000bp"],
    // 빈칸을 0bp로 읽으면 사용자가 넣지 않은 값으로 주문이 정해진다
    ["오프셋 빈칸", { orderType: "LIMIT" as const, offsetBps: "" }, "1000bp"],
    ["계좌 비율 하한", { sizeType: "EQUITY_PCT" as const, equityPct: String(MIN_EQUITY_PCT) }, null],
    ["계좌 비율 상한", { sizeType: "EQUITY_PCT" as const, equityPct: String(MAX_EQUITY_PCT) }, null],
    ["계좌 비율 소수 둘째 자리", { sizeType: "EQUITY_PCT" as const, equityPct: "2.55" }, null],
    ["계좌 비율 0.99", { sizeType: "EQUITY_PCT" as const, equityPct: "0.99" }, "1~25%"],
    ["계좌 비율 25.01", { sizeType: "EQUITY_PCT" as const, equityPct: "25.01" }, "1~25%"],
    ["계좌 비율 소수 셋째 자리", { sizeType: "EQUITY_PCT" as const, equityPct: "2.555" }, "1~25%"],
    ["계좌 비율 빈칸", { sizeType: "EQUITY_PCT" as const, equityPct: "" }, "1~25%"],
    ["중요도 0", { minImportance: "0" }, null],
    ["중요도 100", { minImportance: "100" }, null],
    ["중요도 −1", { minImportance: "-1" }, "0~100"],
    ["중요도 101", { minImportance: "101" }, "0~100"],
    ["중요도 빈칸", { minImportance: "" }, "0~100"],
    ["하루 한도 1", { dailyLimit: "1" }, null],
    ["하루 한도 1000", { dailyLimit: "1000" }, null],
    ["하루 한도 0", { dailyLimit: "0" }, "1~1000"],
    ["하루 한도 1001", { dailyLimit: "1001" }, "1~1000"],
    ["이름 100자", { name: "가".repeat(100) }, null],
    ["이름 101자", { name: "가".repeat(101) }, "100자"],
    ["종목 없음", { stockId: null }, "종목을 선택"],
    ["그룹 없음", { target: "GROUP" as const, groupId: null }, "그룹을 골라"],
  ])("%s", (_label, patch, expected) => {
    const create = errorOf(patch);
    const edit = errorOf(patch, { checkStrategy: false });
    if (expected == null) expect(create).toBeNull();
    else expect(create).toContain(expected);
    // 생성·수정 검증이 같다
    expect(edit).toBe(create);
  });

  it("전략 선택은 생성에서만 본다(수정은 감지 조건을 바꾸지 않는다)", () => {
    expect(errorOf({ eventType: "QUANT_SIGNAL", ruleSetId: "" })).toContain("전략");
    expect(errorOf({ eventType: "QUANT_SIGNAL", ruleSetId: "" }, { checkStrategy: false })).toBeNull();
  });
});

const rule: WatchRuleResponse = {
  id: 5, stockId: 19, eventType: "VOLUME_SURGE", side: "BUY", quantity: 10, minImportanceScore: 30,
  cooldownSec: 600, isActive: true, createdAt: "2026-10-01T00:00:00Z", name: "거래량", dailyLimit: 3,
  targetType: "STOCK", targetGroupId: null, orderType: "MARKET", limitOffsetBps: null, sizeType: "SHARES", equityPct: null,
};

const valueOf = (patch: Partial<WatchRuleFormInput>) => {
  const r = validateWatchRuleForm({
    ...base, minImportance: "30", name: "거래량", dailyLimit: "3", ...patch,
  }, { checkStrategy: false });
  if ("error" in r) throw new Error(r.error);
  return r.value;
};

describe("buildWatchRulePatch — 바뀐 값만", () => {
  it("아무것도 안 바꾸면 빈 본문", () => {
    expect(buildWatchRulePatch(rule, valueOf({}))).toEqual({});
  });

  it("이름 지우기는 빈 문자열, 하루 한도 지우기는 0", () => {
    expect(buildWatchRulePatch(rule, valueOf({ name: "  ", dailyLimit: "" }))).toEqual({ name: "", dailyLimit: 0 });
  });

  it("같은 기준 안의 값만 바꾸면 값만 보낸다", () => {
    expect(buildWatchRulePatch(rule, valueOf({ quantity: "20", cooldownSec: 3600 }))).toEqual({ quantity: 20, cooldownSec: 3600 });
  });

  it("기준이 바뀌면 기준과 새 기준의 값을 함께 보낸다", () => {
    expect(buildWatchRulePatch(rule, valueOf({ sizeType: "EQUITY_PCT", equityPct: "2.5", orderType: "LIMIT", offsetBps: "-50" })))
      .toEqual({ sizeType: "EQUITY_PCT", equityPct: 2.5, orderType: "LIMIT", limitOffsetBps: -50 });
    const limitRule = { ...rule, orderType: "LIMIT" as const, limitOffsetBps: -50 };
    expect(buildWatchRulePatch(limitRule, valueOf({}))).toEqual({ orderType: "MARKET" });
  });

  it("대상: 같은 유형이면 id만, 유형이 바뀌면 유형과 id", () => {
    expect(buildWatchRulePatch(rule, valueOf({ stockId: 42 }))).toEqual({ stockId: 42 });
    expect(buildWatchRulePatch(rule, valueOf({ target: "GROUP", groupId: 7 }))).toEqual({ targetType: "GROUP", targetGroupId: 7 });
  });

  it("계좌 % 비교는 숫자로 한다(서버 BigDecimal 5.00 = 5)", () => {
    const pctRule = { ...rule, quantity: null, sizeType: "EQUITY_PCT" as const, equityPct: 5 };
    expect(buildWatchRulePatch(pctRule, valueOf({ sizeType: "EQUITY_PCT", equityPct: "5.00" }))).toEqual({});
  });

  it("그룹이 지워져 꺼진 규칙은 대상을 바꿀 때만 isActive를 담는다", () => {
    const orphan = { ...rule, stockId: null, targetType: "GROUP" as const, targetGroupId: 3, targetGroupMissing: true, isActive: false };
    expect(buildWatchRulePatch(orphan, valueOf({ target: "GROUP", groupId: 3, stockId: null, name: "새 이름" }), { reactivate: true }))
      .toEqual({ name: "새 이름" });
    expect(buildWatchRulePatch(orphan, valueOf({ target: "GROUP", groupId: 8, stockId: null }), { reactivate: true }))
      .toEqual({ targetGroupId: 8, isActive: true });
    expect(buildWatchRulePatch(orphan, valueOf({ target: "GROUP", groupId: 8, stockId: null }), { reactivate: false }))
      .toEqual({ targetGroupId: 8 });
  });
});

describe("낙관적 반영 범위", () => {
  it("기준·대상·켜기를 건드리는 요청은 낙관적으로 반영하지 않는다", () => {
    expect(isOptimisticSafe({ name: "a", cooldownSec: 0 })).toBe(true);
    expect(isOptimisticSafe({ quantity: 3 })).toBe(true);
    expect(isOptimisticSafe({ stockId: 42 })).toBe(false);
    expect(isOptimisticSafe({ targetType: "GROUP", targetGroupId: 1 })).toBe(false);
    expect(isOptimisticSafe({ orderType: "MARKET" })).toBe(false);
    expect(isOptimisticSafe({ sizeType: "EQUITY_PCT", equityPct: 2 })).toBe(false);
    expect(isOptimisticSafe({ isActive: true })).toBe(false);
    expect(isOptimisticSafe({})).toBe(false);
  });

  it("빈 이름·한도 0은 서버 응답과 같은 모양(null)으로 반영한다", () => {
    expect(applyOptimisticPatch(rule, { name: "", dailyLimit: 0 })).toMatchObject({ name: null, dailyLimit: null });
  });
});
