"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

export interface Holding {
  stockId: number; symbol: string; name: string;
  quantity: number; avgPrice: number; currentPrice: number;
  value: number; pnl: number; pnlRate: number;
}

export interface Portfolio {
  cash: number; totalValue: number; totalPnl: number; totalPnlRate: number;
  holdings: Holding[];
}

export interface TradeHistory {
  id: number; side: string; stockId: number; symbol: string; name: string;
  quantity: number; price: number; amount: number; tradedAt: string;
}

async function fetchPortfolio(): Promise<Portfolio> {
  const r = await authFetch("/api/paper/portfolio");
  if (!r.ok) throw new Error("포트폴리오 조회 실패");
  return r.json();
}

async function fetchHistory(): Promise<TradeHistory[]> {
  const r = await authFetch("/api/paper/history");
  if (!r.ok) return [];
  return r.json();
}

export function usePaperPortfolio() {
  return useQuery<Portfolio>({
    queryKey: ["paper", "portfolio"],
    queryFn: fetchPortfolio,
    refetchInterval: 5_000,
    staleTime: 5_000,
  });
}

export function usePaperHistory() {
  return useQuery<TradeHistory[]>({
    queryKey: ["paper", "history"],
    queryFn: fetchHistory,
    staleTime: 10_000,
  });
}

export function usePaperTrade() {
  const qc = useQueryClient();

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["paper"] });
  };

  const buy = useMutation({
    mutationFn: async ({ stockId, quantity }: { stockId: number; quantity: number }) => {
      const r = await authFetch("/api/paper/buy", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId, quantity }),
      });
      const body = await r.json();
      if (!r.ok) throw new Error(body.error ?? "매수 실패");
      return body;
    },
    onSuccess: refresh,
  });

  const sell = useMutation({
    mutationFn: async ({ stockId, quantity }: { stockId: number; quantity: number }) => {
      const r = await authFetch("/api/paper/sell", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId, quantity }),
      });
      const body = await r.json();
      if (!r.ok) throw new Error(body.error ?? "매도 실패");
      return body;
    },
    onSuccess: refresh,
  });

  const reset = useMutation({
    mutationFn: async () => {
      const r = await authFetch("/api/paper/reset", { method: "POST" });
      if (!r.ok) throw new Error("초기화 실패");
      return r.json();
    },
    onSuccess: refresh,
  });

  return { buy, sell, reset };
}
