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
  /** 모의투자 리스크 체크. 실거래 게이트는 이 값과 무관하게 항상 돈다(ADR-069) */
  isActive: boolean;
  /** 24시간 뒤 적용될 완화 — 위 값은 지금 유효한 한도 */
  pendingChanges: PendingLimitChange[];
  coolingOffHours: number;
}

/** ADR-069 — field는 위 필드 이름, value가 null이면 섹터 한도 해제, isActive는 1/0 */
export interface PendingLimitChange {
  field: string;
  value: number | null;
  requestedAt: string;
  effectiveAt: string;
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
