// 룰셋 빌더·버전 탭이 함께 쓰는 룰 정의 해석. 화면 컴포넌트에서 떼어 내 테스트할 수 있게 둔다.
import type { RuleCondition, RuleOperator } from "@monticker/types";

export const INDICATORS = [
  { value: "CLOSE_VS_MA",    label: "현재가 vs 이동평균",   params: ["period"], comparators: ["GT","LT"] },
  { value: "VOLUME_RATIO",   label: "거래량 배율",           params: ["period"], comparators: ["GT","LT"], hasValue: true },
  { value: "RSI",            label: "RSI",                   params: ["period"], comparators: ["GT","LT","BETWEEN"], hasValue: true },
  { value: "MACD_CROSS",     label: "MACD 크로스",           params: [],         comparators: ["GOLDEN","DEAD"] },
  { value: "PRICE_CHANGE",   label: "N일 가격변화율(%)",     params: ["period"], comparators: ["GT","LT"], hasValue: true },
  { value: "BOLLINGER_BAND", label: "볼린저밴드",            params: ["period"], comparators: ["ABOVE_UPPER","BELOW_LOWER"] },
  // ADR-079 — 보조 데이터 지표. 장 마감(15:30) 이후 나온 뉴스·공시는 다음 거래일부터 반영된다.
  { value: "NEWS_SENTIMENT", label: "뉴스 감성(-1~1)",       params: ["period"], comparators: ["GT","LT"], hasValue: true, step: 0.1, defaultPeriod: 5 },
  { value: "DISCLOSURE",     label: "공시 발생",             params: ["period"], comparators: ["ANY","EARNINGS","BUYBACK","RIGHTS_ISSUE","BONUS_ISSUE","MNA","INSIDER","DIVIDEND"], defaultPeriod: 5 },
  { value: "PROFIT_RATE",    label: "수익률(%)",             params: [],         comparators: ["GTE","LTE"], hasValue: true, exitOnly: true },
  { value: "LOSS_RATE",      label: "손실률(%)",             params: [],         comparators: ["LTE"],       hasValue: true, exitOnly: true },
] as const;

export type IndicatorMeta = (typeof INDICATORS)[number];

export const COMPARATOR_LABEL: Record<string, string> = {
  GT: ">", LT: "<", GTE: "≥", LTE: "≤",
  BETWEEN: "사이", GOLDEN: "골든크로스", DEAD: "데드크로스",
  ABOVE_UPPER: "상단 돌파", BELOW_LOWER: "하단 이탈",
  ANY: "모든 공시", EARNINGS: "실적·정기보고서", BUYBACK: "자사주 취득", RIGHTS_ISSUE: "유상증자",
  BONUS_ISSUE: "무상증자", MNA: "합병·분할·인수", INSIDER: "임원·주요주주 지분", DIVIDEND: "배당 결정",
};

export interface Condition extends RuleCondition {
  id: string;
  params: Record<string, number>;
}

export interface ParsedRuleDefinition {
  entryRules?: { operator?: RuleOperator; conditions?: RuleCondition[] };
  exitRules?: { operator?: RuleOperator; conditions?: RuleCondition[] };
  positionSizing?: { type?: string; value?: number };
  hardExits?: { maxHoldDays?: number; trailingStopPct?: number };
}

/**
 * 룰셋 조회 응답의 ruleDefinition은 JSON 문자열, 버전 이력(`GET /rulesets/{id}/versions`)은 객체로 온다.
 * 둘 다 받아 같은 모양으로 돌려준다. 깨진 문자열은 빈 정의로 본다.
 */
export function parseRuleDefinition(raw: unknown): ParsedRuleDefinition {
  if (raw == null) return {};
  if (typeof raw === "string") {
    try {
      const v = JSON.parse(raw || "{}");
      return v && typeof v === "object" ? v : {};
    } catch {
      return {};
    }
  }
  return typeof raw === "object" ? (raw as ParsedRuleDefinition) : {};
}

/** 서버 조건 목록에 화면용 id를 붙인다. */
export function toConditions(list: RuleCondition[] | undefined, prefix: string): Condition[] {
  return (list ?? []).map((c, i) => ({ ...c, id: `${prefix}${i}`, params: (c as Condition).params ?? {} }));
}

export function indicatorHasValue(meta: IndicatorMeta | undefined): boolean {
  return !!meta && "hasValue" in meta && meta.hasValue;
}

/** 조건 한 개를 사람이 읽는 한 줄로. */
export function condToText(c: RuleCondition & { params?: Record<string, number> }): string {
  const meta = INDICATORS.find(i => i.value === c.indicator);
  const label = meta?.label ?? c.indicator;
  const period = c.params?.period;
  const periodStr = period ? `(${period})` : "";
  const cmpLabel = COMPARATOR_LABEL[c.comparator] ?? c.comparator;

  if (c.indicator === "DISCLOSURE") return `최근 ${period ?? 5}거래일 ${cmpLabel} 공시`;
  if (c.comparator === "GOLDEN") return `${label} — 골든크로스`;
  if (c.comparator === "DEAD")   return `${label} — 데드크로스`;
  if (c.comparator === "ABOVE_UPPER") return `${label}${periodStr} 상단 돌파`;
  if (c.comparator === "BELOW_LOWER") return `${label}${periodStr} 하단 이탈`;
  if (c.comparator === "BETWEEN") {
    const lo = Array.isArray(c.value) ? c.value[0] : "?";
    const hi = Array.isArray(c.value) ? c.value[1] : "?";
    return `${label}${periodStr}  ${lo} ~ ${hi}`;
  }
  const val = typeof c.value === "number" ? c.value : "";
  return `${label}${periodStr}  ${cmpLabel} ${val}`;
}
