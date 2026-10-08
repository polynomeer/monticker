"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

/** GET /api/quant/signals/summary — 퀀트랩 상단 "오늘 신호"(KST 달력일)·"구독 중" */
export interface QuantSignalSummary {
  todaySignals: number;
  activeSubscriptions: number;
  /** 집계 기준 KST 날짜(YYYY-MM-DD) */
  date: string;
}

export function useQuantSignalSummary() {
  return useQuery<QuantSignalSummary>({
    queryKey: ["quant", "signals", "summary"],
    queryFn: async () => {
      const r = await authFetch("/api/quant/signals/summary");
      if (!r.ok) throw new Error("신호 집계 조회 실패");
      return r.json();
    },
    refetchInterval: 60_000,
  });
}

/** 집계 값을 헤더 표시로 — 불러오는 중·실패면 지어낸 0 대신 `—` */
export function countStat(value: number | null | undefined, unit: string): string {
  return typeof value === "number" && Number.isFinite(value) ? `${value}${unit}` : "—";
}
