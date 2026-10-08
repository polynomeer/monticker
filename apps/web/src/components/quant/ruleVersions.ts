// 룰셋 버전 탭 — `GET /api/quant/rulesets/{id}/versions` 응답을 화면 행으로 바꾸고, 이웃 버전과의 차이를 요약한다.
import type { RuleCondition } from "@monticker/types";
import { parseRuleDefinition, type ParsedRuleDefinition } from "./ruleDefinition";

/** 서버 RuleVersionEntry — ruleDefinition은 (룰셋 조회와 달리) 문자열이 아닌 객체로 온다. */
export interface RuleVersionEntry {
  version: number;
  ruleDefinition: Record<string, unknown>;
  fingerprint: string;
  changeSummary: string | null;
  createdAt: string;
}

export interface VersionRow {
  version: number;
  def: ParsedRuleDefinition;
  /** 이 버전이 만들어진 시각. 이력 항목은 다음 버전으로 넘어갈 때 찍힌 시각이라 근사치다. */
  at: string | null;
  isCurrent: boolean;
  /** 이 버전으로 바뀔 때 남긴 메모 */
  memo: string | null;
  /** 바로 이전 버전 대비 변경 요약. 가장 오래된 버전은 null */
  changes: string[] | null;
}

/**
 * 서버는 룰을 고칠 때 "고치기 직전" 정의를 이력에 쌓고, 그때 받은 changeSummary를 그 옛 항목에 붙인다.
 * 즉 이력 vN의 changeSummary는 vN→vN+1 변경 메모다. 화면에서는 메모를 바뀐 쪽(vN+1)에 붙여 보여 준다.
 * 현재 버전은 이력에 없으므로 룰셋 본문(current)으로 맨 위 행을 만든다.
 */
export function buildVersionRows(
  current: { version: number; ruleDefinition?: unknown; updatedAt?: string },
  history: RuleVersionEntry[],
): VersionRow[] {
  const past = history.filter(h => h.version < current.version).sort((a, b) => b.version - a.version);
  const byVersion = new Map(past.map(h => [h.version, h]));
  const raw = [
    { version: current.version, def: parseRuleDefinition(current.ruleDefinition), at: current.updatedAt ?? null, isCurrent: true },
    ...past.map(h => ({ version: h.version, def: parseRuleDefinition(h.ruleDefinition), at: h.createdAt ?? null, isCurrent: false })),
  ];
  return raw.map((r, i) => {
    const prev = raw[i + 1];
    return {
      ...r,
      memo: byVersion.get(r.version - 1)?.changeSummary ?? null,
      changes: prev ? diffDefinitions(prev.def, r.def) : null,
    };
  });
}

function stableKey(c: RuleCondition): string {
  const params = Object.entries(c.params ?? {}).sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify([c.indicator, c.comparator, params, c.value ?? null]);
}

function conditionChanges(label: string, before: RuleCondition[] = [], after: RuleCondition[] = []): string[] {
  const remaining = new Map<string, number>();
  for (const c of before) remaining.set(stableKey(c), (remaining.get(stableKey(c)) ?? 0) + 1);
  let added = 0;
  for (const c of after) {
    const k = stableKey(c);
    const n = remaining.get(k) ?? 0;
    if (n > 0) remaining.set(k, n - 1);
    else added++;
  }
  const removed = [...remaining.values()].reduce((s, n) => s + n, 0);
  if (added || removed) {
    return [`${label} ${[added && `+${added}`, removed && `−${removed}`].filter(Boolean).join(" ")}`];
  }
  const reordered = before.some((c, i) => stableKey(c) !== stableKey(after[i]));
  return reordered ? [`${label} 순서 변경`] : [];
}

const fmt = (v: number | undefined, unit: string) => (v == null ? "없음" : `${v}${unit}`);

/** 두 룰 정의의 차이를 짧은 문장 목록으로. 같으면 빈 배열. */
export function diffDefinitions(before: ParsedRuleDefinition, after: ParsedRuleDefinition): string[] {
  const out: string[] = [];
  const op = (v?: string, fallback = "AND") => (v ?? fallback) === "AND" ? "모두" : "하나라도";

  if ((before.entryRules?.operator ?? "AND") !== (after.entryRules?.operator ?? "AND")) {
    out.push(`매수 결합 ${op(before.entryRules?.operator)}→${op(after.entryRules?.operator)}`);
  }
  out.push(...conditionChanges("매수 조건", before.entryRules?.conditions, after.entryRules?.conditions));
  if ((before.exitRules?.operator ?? "OR") !== (after.exitRules?.operator ?? "OR")) {
    out.push(`매도 결합 ${op(before.exitRules?.operator, "OR")}→${op(after.exitRules?.operator, "OR")}`);
  }
  out.push(...conditionChanges("매도 조건", before.exitRules?.conditions, after.exitRules?.conditions));

  const pb = before.positionSizing?.value, pa = after.positionSizing?.value;
  if (pb !== pa) out.push(`1회 투입 ${fmt(pb, "%")}→${fmt(pa, "%")}`);
  const hb = before.hardExits?.maxHoldDays, ha = after.hardExits?.maxHoldDays;
  if (hb !== ha) out.push(`최대 보유 ${fmt(hb, "거래일")}→${fmt(ha, "거래일")}`);
  const tb = before.hardExits?.trailingStopPct, ta = after.hardExits?.trailingStopPct;
  if (tb !== ta) out.push(`트레일링 ${fmt(tb, "%")}→${fmt(ta, "%")}`);
  return out;
}
