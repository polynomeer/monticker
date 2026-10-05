"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

/** GET /api/risk/decisions — 리스크 게이트가 막은 주문(본인 것만) */
export interface RiskDecision {
  id: number;
  createdAt: string;
  accountType: "PAPER" | "REAL" | string;
  stockId: number | null;
  symbol: string | null;
  stockName: string | null;
  side: "BUY" | "SELL" | string | null;
  quantity: number | null;
  blockedBy: string | null;
  detail: string | null;
}

export interface RiskDecisionPage { items: RiskDecision[]; page: number; size: number; hasNext: boolean; }
export interface RiskDecisionSummary { blockedThisMonth: number; monthStart: string; }

/** 서버 규칙 이름 → 화면 이름 */
export const RULE_LABELS: Record<string, string> = {
  DailyLossRule: "일일 손실",
  ConcentrationRule: "단일 종목 집중도",
  SectorConcentrationRule: "섹터 집중도",
  VaRRule: "1일 VaR",
  PositionCountRule: "보유 종목 수",
  TradingFrequencyRule: "주문 빈도",
  QuantityRule: "주문 수량",
};

export function useRiskDecisions(page: number, enabled = true) {
  return useQuery<RiskDecisionPage>({
    queryKey: ["risk", "decisions", page],
    queryFn: async () => {
      const r = await authFetch(`/api/risk/decisions?page=${page}&size=20`);
      if (!r.ok) throw new Error("차단 기록 조회 실패");
      return r.json();
    },
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
    enabled,
  });
}

export function useRiskDecisionSummary(enabled = true) {
  return useQuery<RiskDecisionSummary>({
    queryKey: ["risk", "decisions", "summary"],
    queryFn: async () => {
      const r = await authFetch("/api/risk/decisions/summary");
      if (!r.ok) throw new Error("차단 집계 조회 실패");
      return r.json();
    },
    refetchInterval: 60_000,
    enabled,
  });
}
