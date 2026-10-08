"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { isInterestSector, type InterestSector } from "@/lib/interestSectors";

export interface AlertStats {
  totalFired: number; totalSent: number; totalFailed: number;
  successRate: number; activeRules: number;
  recentFires: { date: string; count: number }[];
  /** 읽지 않은 알림 수(ADR-073) */
  unread?: number;
}

/** GET /api/alerts/history/search 응답(AlertHistoryResponse) */
export interface AlertHistory {
  id: number;
  /** 규칙 없이 생긴 이력(퀀트 시그널, ADR-090)은 null */
  ruleId: number | null;
  stockId: number | null;
  ruleType: string;
  message: string;
  deliveryStatus: string;
  triggeredAt: string;
  /** null이면 읽지 않음(ADR-073) */
  readAt?: string | null;
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

/**
 * 알림 화면의 규칙 목록 — 꺼 둔 규칙도 포함(includePaused). 다른 화면(관심종목)이 쓰는
 * ["alerts","rules"](켜진 규칙만)과 키를 나눠 캐시가 섞이지 않게 한다.
 */
export function useAlertRules(enabled: boolean) {
  return useQuery<AlertRule[]>({
    queryKey: ["alerts", "rules", "all"],
    queryFn: async () => {
      const r = await authFetch("/api/alerts/rules?includePaused=true");
      return r.ok ? r.json() : [];
    },
    enabled,
  });
}

async function failWith(r: Response): Promise<never> {
  const body = await r.json().catch(() => null);
  throw new Error(body?.message ?? `요청이 실패했습니다 (${r.status})`);
}

/** 규칙 켜기/끄기 · 읽음 처리(ADR-073). 성공하면 알림 관련 캐시를 모두 새로 받는다. */
export function useAlertMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: ["alerts"] });

  const toggleRule = useMutation({
    mutationFn: async ({ id, isActive }: { id: number; isActive: boolean }) => {
      const r = await authFetch(`/api/alerts/rules/${id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ isActive }),
      });
      if (!r.ok) await failWith(r);
      return (await r.json()) as AlertRule;
    },
    onSuccess: invalidate,
  });

  const markRead = useMutation({
    mutationFn: async (id: number) => {
      const r = await authFetch(`/api/alerts/history/${id}/read`, { method: "POST" });
      if (!r.ok && r.status !== 404) await failWith(r);
    },
    onSuccess: invalidate,
  });

  /** upTo: 화면에 보이는 목록을 받은 시각 — 그 뒤에 온 알림은 남긴다 */
  const markAllRead = useMutation({
    mutationFn: async (upTo: Date) => {
      const r = await authFetch(`/api/alerts/history/read-all?upTo=${encodeURIComponent(upTo.toISOString())}`, { method: "POST" });
      if (!r.ok) await failWith(r);
    },
    onSuccess: invalidate,
  });

  return { toggleRule, markRead, markAllRead };
}

/** 알림 종류 → 시안의 원형 글자 배지·분류 탭 */
export type AlertCategory = "price" | "event" | "signal" | "account";

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
  // ADR-090 — 내 전략·구독 전략의 포워드 테스트 신호(규칙 없이 서버가 적재)
  QUANT_SIGNAL:         { letter: "Q", color: "#50fa7b", tag: "시그널", category: "signal" },
};

export function ruleMeta(type: string) {
  return RULE_META[type] ?? { letter: "?", color: "#c3c8e2", tag: type, category: "event" as AlertCategory };
}

export type AlertFilter = "all" | "unread" | AlertCategory;

/** 알림 이력 탭 필터 */
export function matchesFilter(a: Pick<AlertHistory, "ruleType" | "readAt">, filter: AlertFilter) {
  if (filter === "all") return true;
  if (filter === "unread") return !a.readAt;
  return ruleMeta(a.ruleType).category === filter;
}

/**
 * ADR-099 — 알림 이력의 "관심 분야" 칩. 종목의 업종(시세 응답)이 고른 관심 분야에 해당하는 알림만 남긴다.
 * 사용자가 칩을 고를 때만 쓰는 화면 필터다 — 기본(전체)은 그대로이고, 발송·읽음·방해 금지 시간과는 무관하다.
 * 종목이 없는 알림(관심종목 전체·시그널)이나 업종을 모르는 종목은 해당하지 않는다.
 */
export function matchesInterest(
  a: Pick<AlertHistory, "stockId">,
  sectorOf: (stockId: number) => string | null | undefined,
  interests: readonly InterestSector[],
) {
  return a.stockId != null && isInterestSector(sectorOf(a.stockId), interests);
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
