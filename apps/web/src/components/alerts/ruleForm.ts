// 알림 화면 "새 알림 규칙" 폼의 순수 로직 — 서버 AlertRuleConditions.isValid와 같은 필수 조건에
// 명백히 말이 안 되는 값(0 이하 가격, 0~100 밖 RSI 등)을 걸러내는 범위 검사를 더한다.

/** 만들 수 있는 유형 — NEWS/DISCLOSURE는 평가기가 없어 서버가 거부한다 */
export const CREATABLE_RULE_TYPES = [
  { value: "PRICE_ABOVE",    label: "가격 이상" },
  { value: "PRICE_BELOW",    label: "가격 이하" },
  { value: "VOLUME_SURGE",   label: "거래량 급증" },
  { value: "RSI_BELOW",      label: "RSI 과매도" },
  { value: "RSI_ABOVE",      label: "RSI 과매수" },
  { value: "PRICE_BELOW_MA", label: "이동평균 하향 이탈" },
  { value: "PRICE_ABOVE_MA", label: "이동평균 상향 돌파" },
  { value: "HOLDING_DROP",   label: "보유종목 하락 (모의투자)" },
] as const;
export type CreatableRuleType = (typeof CREATABLE_RULE_TYPES)[number]["value"];

export interface RuleDraft {
  stockId: number | null;
  ruleType: CreatableRuleType;
  /** 문자열 그대로(입력창 값) */
  threshold: string;
  period: string;
  dropPct: string;
}

export function defaultDraft(ruleType: CreatableRuleType = "PRICE_ABOVE", stockId: number | null = null): RuleDraft {
  const rsi = ruleType === "RSI_BELOW" ? "30" : ruleType === "RSI_ABOVE" ? "70" : "";
  const period = ruleType === "PRICE_BELOW_MA" || ruleType === "PRICE_ABOVE_MA" ? "20" : "14";
  return { stockId, ruleType, threshold: rsi, period, dropPct: "10" };
}

export type RuleErrors = Partial<Record<"stockId" | "threshold" | "period" | "dropPct", string>>;

export const PERIOD_MIN = 2;
export const PERIOD_MAX = 250;

const num = (v: string) => (v.trim() === "" ? NaN : Number(v));

/** 검증과 요청 본문 만들기. 오류가 하나라도 있으면 body는 null */
export function buildRuleRequest(d: RuleDraft): { errors: RuleErrors; body: { stockId: number; ruleType: CreatableRuleType; condition: Record<string, number> } | null } {
  const errors: RuleErrors = {};
  if (!d.stockId) errors.stockId = "종목을 고르세요.";

  const condition: Record<string, number> = {};
  const needPeriod = (fallback: number) => {
    const p = d.period.trim() === "" ? fallback : num(d.period);
    if (!Number.isInteger(p) || p < PERIOD_MIN || p > PERIOD_MAX) errors.period = `기간은 ${PERIOD_MIN}~${PERIOD_MAX}일 정수입니다.`;
    else condition.period = p;
  };

  switch (d.ruleType) {
    case "PRICE_ABOVE":
    case "PRICE_BELOW": {
      const t = num(d.threshold);
      if (!Number.isFinite(t)) errors.threshold = "기준 가격을 입력하세요.";
      else if (t <= 0) errors.threshold = "기준 가격은 0보다 커야 합니다.";
      else condition.threshold = t;
      break;
    }
    case "RSI_BELOW":
    case "RSI_ABOVE": {
      const t = num(d.threshold);
      if (!Number.isFinite(t)) errors.threshold = "RSI 기준값을 입력하세요.";
      else if (t <= 0 || t >= 100) errors.threshold = "RSI 기준값은 0과 100 사이입니다.";
      else condition.threshold = t;
      needPeriod(14);
      break;
    }
    case "PRICE_BELOW_MA":
    case "PRICE_ABOVE_MA":
      needPeriod(20);
      break;
    case "HOLDING_DROP": {
      const p = num(d.dropPct);
      if (!Number.isFinite(p)) errors.dropPct = "하락률을 입력하세요.";
      else if (p <= 0 || p > 100) errors.dropPct = "하락률은 0 초과 100 이하(%)입니다.";
      else condition.dropPct = p;
      break;
    }
    case "VOLUME_SURGE":
      break;
  }

  const ok = Object.keys(errors).length === 0;
  return { errors, body: ok ? { stockId: d.stockId!, ruleType: d.ruleType, condition } : null };
}

/** POST /api/alerts/rules 실패 응답 → 사람이 읽는 문장 */
export function createRuleErrorMessage(status: number, serverMessage?: string | null): string {
  if (status === 400) return "조건이 올바르지 않아 서버가 거부했습니다. 입력값을 확인하세요.";
  if (status === 401 || status === 403) return "로그인이 만료되었습니다. 다시 로그인하세요.";
  if (status === 409) return serverMessage ?? "이미 같은 규칙이 있거나 지금은 만들 수 없는 규칙입니다.";
  if (status === 429) return "알림 규칙은 1시간에 20개까지 만들 수 있습니다. 잠시 후 다시 시도하세요.";
  return serverMessage ?? `알림 규칙을 만들지 못했습니다 (${status}).`;
}
