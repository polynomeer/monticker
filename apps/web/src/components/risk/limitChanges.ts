import type { PendingLimitChange, RiskLimits } from "./useRiskExposure";

/** 한도 항목(서버 RiskLimitField.key와 같은 이름) */
export type LimitKey =
  | "dailyLossLimitPct"
  | "concentrationLimitPct"
  | "varLimitPct"
  | "maxPositionCount"
  | "maxHourlyOrders"
  | "sectorConcentrationLimitPct"
  | "isActive";

export const LIMIT_LABELS: Record<LimitKey, { label: string; unit: string }> = {
  varLimitPct: { label: "1일 VaR 한도", unit: "%" },
  concentrationLimitPct: { label: "단일 종목 최대 비중", unit: "%" },
  sectorConcentrationLimitPct: { label: "섹터 최대 비중", unit: "%" },
  dailyLossLimitPct: { label: "일일 최대 손실", unit: "%" },
  maxPositionCount: { label: "최대 보유 종목 수", unit: "개" },
  maxHourlyOrders: { label: "1시간 최대 주문 수", unit: "회" },
  isActive: { label: "리스크 체크", unit: "" },
};

const NUM_KEYS = ["dailyLossLimitPct", "concentrationLimitPct", "varLimitPct", "maxPositionCount", "maxHourlyOrders"] as const;

/**
 * PUT /api/risk/limits 본문 — 서버 값과 달라진 항목만 보낸다(ADR-069). 서버는 보낸 항목마다 강화면 즉시, 완화면 24시간 뒤에
 * 적용하고, 현재 값과 같으면 그 항목의 대기 변경을 취소한다. 그래서 바꾸지 않은 항목을 함께 보내면 대기 중인 완화가 취소된다.
 */
export function limitsPayload(server: RiskLimits, draft: RiskLimits): Record<string, number | boolean> {
  const body: Record<string, number | boolean> = {};
  for (const k of NUM_KEYS) if (draft[k] !== server[k]) body[k] = draft[k];
  if (draft.isActive !== server.isActive) body.isActive = draft.isActive;
  const s = draft.sectorConcentrationLimitPct;
  if (s !== server.sectorConcentrationLimitPct) {
    if (s == null) body.clearSectorConcentrationLimit = true;
    else body.sectorConcentrationLimitPct = s;
  }
  return body;
}

/** 대기 중인 완화를 취소하는 본문 — 현재 값을 그대로 보내면 서버가 그 항목의 대기 변경을 지운다. */
export function cancelPayload(server: RiskLimits, field: string): Record<string, number | boolean> | null {
  if (field === "sectorConcentrationLimitPct") {
    const v = server.sectorConcentrationLimitPct;
    return v == null ? { clearSectorConcentrationLimit: true } : { sectorConcentrationLimitPct: v };
  }
  if (field === "isActive") return { isActive: server.isActive };
  if ((NUM_KEYS as readonly string[]).includes(field)) return { [field]: server[field as (typeof NUM_KEYS)[number]] };
  return null;
}

function fmtValue(field: string, v: number | null): string {
  if (field === "isActive") return v === 0 ? "끔" : "켬";
  if (v == null) return "미설정";
  const unit = LIMIT_LABELS[field as LimitKey]?.unit ?? "";
  return `${v}${unit}`;
}

/** "1일 VaR 한도 5% → 8%" */
export function describePending(server: RiskLimits, p: PendingLimitChange): string {
  const label = LIMIT_LABELS[p.field as LimitKey]?.label ?? p.field;
  const current =
    p.field === "isActive" ? (server.isActive ? 1 : 0) : (server[p.field as keyof RiskLimits] as number | null | undefined) ?? null;
  return `${label} ${fmtValue(p.field, current)} → ${fmtValue(p.field, p.value)}`;
}

/** "10.07 14:00" (KST) — 적용 시각·판정 시각 표시 */
export function fmtKst(iso: string): string {
  const parts = new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(new Date(iso));
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  return `${get("month")}.${get("day")} ${get("hour")}:${get("minute")}`;
}

/** 서버 응답에서 이번 요청으로 새로 대기에 들어간(또는 남은) 항목 — 저장 안내에 쓴다. */
export function pendingFor(sent: Record<string, unknown>, pending: PendingLimitChange[]): PendingLimitChange[] {
  const keys = new Set(Object.keys(sent).map((k) => (k === "clearSectorConcentrationLimit" ? "sectorConcentrationLimitPct" : k)));
  return pending.filter((p) => keys.has(p.field));
}
