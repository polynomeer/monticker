"use client";

import { useQuery } from "@tanstack/react-query";

export interface VwapPoint { time: number; vwap: string; }

export function useVwap(stockId: number | null) {
  const { data } = useQuery<VwapPoint[]>({
    queryKey: stockId ? ["stocks", stockId, "vwap"] : ["disabled"],
    queryFn:  async () => {
      const r = await fetch(`/api/stocks/${stockId}/vwap/series`);
      return r.ok ? r.json() : [];
    },
    enabled:         !!stockId,
    refetchInterval: 30_000,
    staleTime:       30_000,
  });
  return { data: data ?? [] };
}
