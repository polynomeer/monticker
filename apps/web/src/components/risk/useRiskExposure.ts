"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

export interface RiskLimits {
  dailyLossLimitPct: number;
  concentrationLimitPct: number;
  varLimitPct: number;
  maxPositionCount: number;
  maxHourlyOrders: number;
  /** 섹터 합산 비중 한도(%) — null이면 미설정(ADR-069) */
  sectorConcentrationLimitPct: number | null;
  isActive: boolean;
}

export interface ConcentrationItem { stockId: number; symbol: string; valuePct: number; }

export interface RiskExposure {
  totalAssets: number;
  availableCash: number;
  dailyPnl: number;
  dailyPnlPct: number;
  topConcentration: ConcentrationItem | null;
  estimatedVaR: number;
  activeOrderCount: number;
  hourlyOrderCount: number;
  limits: RiskLimits;
}

/** GET /api/risk/exposure — 리스크 화면과 지갑 상단(오늘 손익)이 같이 쓴다. */
export function useRiskExposure(enabled = true) {
  return useQuery<RiskExposure>({
    queryKey: ["risk", "exposure"],
    queryFn: async () => {
      const r = await authFetch("/api/risk/exposure");
      if (!r.ok) throw new Error("노출도 조회 실패");
      return r.json();
    },
    refetchInterval: 15_000,
    enabled,
  });
}
