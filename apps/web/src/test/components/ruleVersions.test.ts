import { describe, it, expect } from "vitest";
import { buildVersionRows, diffDefinitions, type RuleVersionEntry } from "@/components/quant/ruleVersions";
import { parseRuleDefinition, type ParsedRuleDefinition } from "@/components/quant/ruleDefinition";

const rsi = { indicator: "RSI", comparator: "LT", params: { period: 14 }, value: 30 };
const vol = { indicator: "VOLUME_RATIO", comparator: "GT", params: { period: 20 }, value: 2 };
const ma = { indicator: "CLOSE_VS_MA", comparator: "GT", params: { period: 20 } };
const tp = { indicator: "PROFIT_RATE", comparator: "GTE", params: {}, value: 8 };

const def = (entry: object[], extra: object = {}) => ({
  entryRules: { operator: "AND", conditions: entry },
  exitRules: { operator: "OR", conditions: [tp] },
  positionSizing: { type: "FIXED_RATIO", value: 10 },
  ...extra,
}) as ParsedRuleDefinition & Record<string, unknown>;

describe("diffDefinitions", () => {
  it("같은 정의는 빈 목록", () => {
    expect(diffDefinitions(def([rsi, vol]), def([rsi, vol]))).toEqual([]);
  });

  it("조건 추가·삭제 수를 센다(파라미터 키 순서는 무시)", () => {
    const rsiReordered = { ...rsi, params: { period: 14 } };
    expect(diffDefinitions(def([rsi, vol]), def([rsiReordered, ma]))).toEqual(["매수 조건 +1 −1"]);
    expect(diffDefinitions(def([rsi]), def([rsi, vol, ma]))).toEqual(["매수 조건 +2"]);
  });

  it("같은 조건의 순서만 바뀌면 순서 변경으로 표시", () => {
    expect(diffDefinitions(def([rsi, vol]), def([vol, rsi]))).toEqual(["매수 조건 순서 변경"]);
  });

  it("결합 방식·사이징·강제 청산 변경", () => {
    const after = {
      ...def([rsi]),
      entryRules: { operator: "OR", conditions: [rsi] },
      positionSizing: { value: 20 },
      hardExits: { maxHoldDays: 15 },
    };
    expect(diffDefinitions(parseRuleDefinition(def([rsi])), parseRuleDefinition(after))).toEqual([
      "매수 결합 모두→하나라도",
      "1회 투입 10%→20%",
      "최대 보유 없음→15거래일",
    ]);
  });
});

describe("buildVersionRows", () => {
  const history: RuleVersionEntry[] = [
    { version: 1, ruleDefinition: def([rsi]), fingerprint: "f1", changeSummary: null, createdAt: "2026-10-01T00:00:00Z" },
    { version: 2, ruleDefinition: def([rsi, vol]), fingerprint: "f2", changeSummary: "v1 룰 복원", createdAt: "2026-10-02T00:00:00Z" },
  ];
  const current = { version: 3, ruleDefinition: JSON.stringify(def([rsi])), updatedAt: "2026-10-03T00:00:00Z" };

  it("현재 버전(룰셋 본문)을 맨 위에 두고 최신순으로 정렬한다", () => {
    const rows = buildVersionRows(current, [...history].reverse());
    expect(rows.map(r => r.version)).toEqual([3, 2, 1]);
    expect(rows[0].isCurrent).toBe(true);
    expect(rows[0].def.entryRules?.conditions).toHaveLength(1);
  });

  it("각 버전에 이전 버전 대비 변경을 붙이고, 가장 오래된 버전은 null", () => {
    const rows = buildVersionRows(current, history);
    expect(rows[0].changes).toEqual(["매수 조건 −1"]);
    expect(rows[1].changes).toEqual(["매수 조건 +1"]);
    expect(rows[2].changes).toBeNull();
  });

  it("서버가 옛 버전에 붙인 변경 메모를 바뀐 쪽(다음 버전) 행에 보여 준다", () => {
    const rows = buildVersionRows(current, history);
    expect(rows.find(r => r.version === 3)?.memo).toBe("v1 룰 복원");
    expect(rows.find(r => r.version === 2)?.memo).toBeNull();
  });

  it("이력이 없으면 현재 버전 한 줄만", () => {
    const rows = buildVersionRows({ version: 1, ruleDefinition: "{bad json" }, []);
    expect(rows).toHaveLength(1);
    expect(rows[0].changes).toBeNull();
    expect(rows[0].def).toEqual({});
  });
});
