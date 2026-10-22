import { formatWon } from "./format";

/** POST /api/risk/preview 결과(ADR-092 — 감사 기록 없는 미리보기). 실제 주문 때 서버가 다시 판정한다 */
export interface RiskRuleResult { rule: string; passed: boolean; detail: string; current: number; limit: number }
export interface RiskCheckResult { approved: boolean; blockedBy: string | null; severity: string; checks: RiskRuleResult[] }

export const RULE_LABEL: Record<string, string> = {
  VaRRule: "1일 VaR 95%",
  ConcentrationRule: "종목 집중도",
  DailyLossRule: "일간 손실 한도",
  PositionCountRule: "보유 종목 수",
  TradingFrequencyRule: "시간당 주문 수",
  SectorConcentrationRule: "섹터 집중도",
  QuantityRule: "주문 수량",
};

/** 시안의 세 줄(일간 손실·집중도·시간당 주문)을 먼저, 나머지는 걸렸을 때만 */
const PRIMARY = ["DailyLossRule", "ConcentrationRule", "TradingFrequencyRule"];

export function visibleChecks(checks: RiskRuleResult[]): RiskRuleResult[] {
  const primary = PRIMARY.map((r) => checks.find((c) => c.rule === r)).filter((c): c is RiskRuleResult => !!c);
  const failedOthers = checks.filter((c) => !PRIMARY.includes(c.rule) && !c.passed);
  return [...primary, ...failedOthers];
}

export function ruleValue(r: RiskRuleResult): string {
  switch (r.rule) {
    case "VaRRule":
    case "ConcentrationRule":
    case "SectorConcentrationRule":
      return `${r.current.toFixed(1)}% / ${r.limit}%`;
    case "PositionCountRule":
      return `${formatWon(r.current)}개 / ${formatWon(r.limit)}개`;
    case "TradingFrequencyRule":
      return `${formatWon(r.current)}건 / ${formatWon(r.limit)}건`;
    case "DailyLossRule":
      // 서버는 손익(이익 +, 손실 −)과 음수 한도를 보낸다 — 손실 크기로 보인다
      return `손실 ${formatWon(Math.max(0, -r.current))} / ${formatWon(Math.abs(r.limit))}원`;
    default:
      return r.passed ? "통과" : "차단";
  }
}
