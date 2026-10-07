"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

export interface AlertStats {
  totalFired: number; totalSent: number; totalFailed: number;
  successRate: number; activeRules: number;
  recentFires: { date: string; count: number }[];
}

/** GET /api/alerts/history/search 응답(AlertHistoryResponse) */
export interface AlertHistory {
  id: number;
  ruleId: number;
  stockId: number | null;
  ruleType: string;
  message: string;
  deliveryStatus: string;
  triggeredAt: string;
}

export interface AlertRule {
  id: number;
  stockId: number | null;
  ruleType: string;
  conditionJson: string;
  isActive: boolean;
  createdAt: string;
}

export function useAlertStats(enabled: boolean) {
  return useQuery<AlertStats | null>({
    queryKey: ["alerts", "stats"],
    queryFn: async () => {
      const r = await authFetch("/api/alerts/stats");
      return r.ok ? r.json() : null;
    },
    enabled,
  });
}

/**
 * 알림 이력. 이전 화면은 서버에 없는 GET /api/alerts/history 를 불러 항상 빈 목록이었다 —
 * 실제로 있는 검색 엔드포인트(조건 없이 부르면 내 최근 이력)를 쓴다.
 */
export function useAlertHistory(enabled: boolean) {
  return useQuery<AlertHistory[]>({
    queryKey: ["alerts", "history"],
    queryFn: async () => {
      const r = await authFetch("/api/alerts/history/search?limit=50");
      return r.ok ? r.json() : [];
    },
    enabled,
    refetchInterval: 30_000,
  });
}

export function useAlertRules(enabled: boolean) {
  return useQuery<AlertRule[]>({
    queryKey: ["alerts", "rules"],
    queryFn: async () => {
      const r = await authFetch("/api/alerts/rules");
      return r.ok ? r.json() : [];
    },
    enabled,
  });
}

/** 알림 종류 → 시안의 원형 글자 배지·분류 탭 */
export type AlertCategory = "price" | "event" | "account";

export const RULE_META: Record<string, { letter: string; color: string; tag: string; category: AlertCategory }> = {
  PRICE_ABOVE:          { letter: "P", color: "#f1fa8c", tag: "가격", category: "price" },
  PRICE_BELOW:          { letter: "P", color: "#f1fa8c", tag: "가격", category: "price" },
  PRICE_ABOVE_MA:       { letter: "M", color: "#f1fa8c", tag: "가격", category: "price" },
  PRICE_BELOW_MA:       { letter: "M", color: "#f1fa8c", tag: "가격", category: "price" },
  RSI_ABOVE:            { letter: "R", color: "#8be9fd", tag: "지표", category: "price" },
  RSI_BELOW:            { letter: "R", color: "#8be9fd", tag: "지표", category: "price" },
  VOLUME_SURGE:         { letter: "V", color: "#bd93f9", tag: "거래량", category: "event" },
  NEWS_PUBLISHED:       { letter: "N", color: "#8be9fd", tag: "뉴스", category: "event" },
  DISCLOSURE_PUBLISHED: { letter: "D", color: "#ffb86c", tag: "공시", category: "event" },
  HOLDING_DROP:         { letter: "H", color: "#ff8a8a", tag: "보유 종목", category: "account" },
};

export function ruleMeta(type: string) {
  return RULE_META[type] ?? { letter: "?", color: "#c3c8e2", tag: type, category: "event" as AlertCategory };
}

/** 규칙 조건을 사람이 읽는 문장으로 */
export function describeRule(rule: { ruleType: string; conditionJson: string }) {
  let c: Record<string, unknown> = {};
  try { c = JSON.parse(rule.conditionJson || "{}"); } catch { /* 형식이 깨졌으면 종류만 */ }
  const n = (k: string) => (typeof c[k] === "number" ? (c[k] as number) : null);
  switch (rule.ruleType) {
    case "PRICE_ABOVE": return n("threshold") != null ? `${n("threshold")!.toLocaleString("ko-KR")}원 이상` : "가격 이상";
    case "PRICE_BELOW": return n("threshold") != null ? `${n("threshold")!.toLocaleString("ko-KR")}원 이하` : "가격 이하";
    case "PRICE_ABOVE_MA": return `이동평균선 상향 돌파${n("period") ? ` (${n("period")}일)` : ""}`;
    case "PRICE_BELOW_MA": return `이동평균선 하향 이탈${n("period") ? ` (${n("period")}일)` : ""}`;
    case "RSI_ABOVE": return `RSI ${n("threshold") ?? ""} 이상`.trim();
    case "RSI_BELOW": return `RSI ${n("threshold") ?? ""} 이하`.trim();
    case "VOLUME_SURGE": return `거래량 ${n("surgeRatio") ?? 2}× 이상 급증`;
    case "HOLDING_DROP": return n("dropPct") != null ? `보유 종목 -${n("dropPct")}% 하락` : "보유 종목 하락";
    case "NEWS_PUBLISHED": return "뉴스 발생";
    case "DISCLOSURE_PUBLISHED": return "공시 발생";
    default: return rule.ruleType;
  }
}
