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
      if (!r.ok) throw new Error(body.message ?? "매수 실패");
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
      if (!r.ok) throw new Error(body.message ?? "매도 실패");
      return body;
    },
    onSuccess: refresh,
  });

  const reset = useMutation({
    mutationFn: async () => {
      const r = await authFetch("/api/paper/reset", { method: "POST" });
      // 성공은 204 No Content — 본문이 없으니 json()을 부르면 성공인데도 실패로 떨어진다.
      // 실패(409: 미체결 주문 있음 등)는 서버 메시지를 그대로 보여 준다.
      if (!r.ok) {
        const body = await r.json().catch(() => null);
        throw new Error(body?.message ?? "초기화 실패");
      }
    },
    onSuccess: refresh,
  });

  return { buy, sell, reset };
}

/** ADR-074 — 모의투자 미체결 주문(매칭 엔진 `orders`, 같은 계좌). */
export interface PaperOpenOrder {
  id: number; stockId: number; side: "BUY" | "SELL"; orderType: "MARKET" | "LIMIT";
  quantity: number; limitPrice: number | null; filledQty: number;
  avgFillPrice: number | null; status: string; rejectReason: string | null; createdAt: string;
}

export interface PaperOrderInput {
  stockId: number; side: "BUY" | "SELL"; orderType: "MARKET" | "LIMIT"; quantity: number; limitPrice?: number;
}

export interface PaperOrderResult {
  orderId: number | null; status: "FILLED" | "PENDING" | string; orderType: string; side: string;
  stockId: number; quantity: number; limitPrice: number | null; price: number | null; amount: number | null;
  remainingCash: number; tradeId: number | null;
}

/** 미체결 지정가 — 매칭 화면과 같은 query key를 써서 한쪽에서 취소하면 양쪽이 같이 갱신된다. */
export function usePaperOpenOrders(enabled = true) {
  return useQuery<PaperOpenOrder[]>({
    queryKey: ["matching", "orders"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/orders");
      if (!r.ok) return [];
      return r.json();
    },
    refetchInterval: 5_000,
    enabled,
  });
}

export function usePaperOrder() {
  const qc = useQueryClient();
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["paper"] });
    qc.invalidateQueries({ queryKey: ["matching"] });
  };

  const place = useMutation({
    mutationFn: async (input: PaperOrderInput): Promise<PaperOrderResult> => {
      const r = await authFetch("/api/paper/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(input),
      });
      const body = await r.json().catch(() => null);
      if (!r.ok) throw new Error(body?.message ?? "주문 실패");
      return body;
    },
    onSuccess: refresh,
  });

  const cancel = useMutation({
    mutationFn: async (orderId: number) => {
      const r = await authFetch(`/api/matching/orders/${orderId}`, { method: "DELETE" });
      if (!r.ok) {
        const body = await r.json().catch(() => null);
        throw new Error(body?.message ?? "취소 실패");
      }
    },
    onSuccess: refresh,
  });

  return { place, cancel };
}

/** 매도 가능 수량 = 보유 − 미체결 매도 잔량(서버 사가와 같은 규칙, ADR-074). */
export function sellableQuantity(owned: number, openOrders: PaperOpenOrder[], stockId: number) {
  const pendingSell = openOrders
    .filter((o) => o.stockId === stockId && o.side === "SELL")
    .reduce((s, o) => s + (o.quantity - o.filledQty), 0);
  return Math.max(0, owned - pendingSell);
}
