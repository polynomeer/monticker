"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { mergeLevels } from "@/components/stock/OrderBook";
import type { ExecutionQuality } from "@/components/wallet/insights";

export const STOCKS = [
  { id: 2, label: "삼성전자" }, { id: 3, label: "SK하이닉스" },
  { id: 9, label: "현대차" },   { id: 10, label: "NAVER" },
  { id: 5, label: "AAPL" },       { id: 6, label: "NVDA" },
];

export interface OrderDto {
  id: number; stockId: number; side: string; orderType: string;
  quantity: number; limitPrice: number | null; filledQty: number;
  avgFillPrice: number | null; status: string;
  rejectReason: string | null; createdAt: string;
}

export interface FillDto {
  id: number; orderId: number; stockId: number; side: string;
  quantity: number; fillPrice: number; amount: number; fee: number; filledAt: string;
}

export interface BookLevel { price: number; quantity: number; amount: number; }
export interface OrderBookData {
  stockId: number; symbol: string; currentPrice: number;
  /** asks[0]이 가장 낮은(가장 가까운) 매도호가 */
  asks: BookLevel[];
  /** bids[0]이 가장 높은 매수호가 */
  bids: BookLevel[];
}

export function useOrderbook(stockId: number) {
  return useQuery<OrderBookData | null>({
    queryKey: ["stocks", stockId, "orderbook"],
    queryFn: async () => {
      const r = await fetch(`/api/stocks/${stockId}/orderbook`);
      if (!r.ok) return null;
      // 종목 화면 OrderBook과 같은 query key를 쓰므로 같은 정규화를 거친다(같은 가격 레벨 병합)
      const raw: OrderBookData = await r.json();
      return { ...raw, asks: mergeLevels(raw.asks), bids: mergeLevels(raw.bids) };
    },
    refetchInterval: 1000,
  });
}

export function useActiveOrders(enabled = true) {
  return useQuery<OrderDto[]>({
    queryKey: ["matching", "orders"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/orders");
      if (!r.ok) return [];
      return r.json();
    },
    refetchInterval: 5000,
    enabled,
  });
}

export function useMyFills(enabled = true) {
  return useQuery<FillDto[]>({
    queryKey: ["matching", "fills"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/fills");
      if (!r.ok) return [];
      return r.json();
    },
    enabled,
  });
}

/** ADR-091 — 내 모의 체결의 평균 슬리피지·엔진 지연(최근 30일, 서버 기본값). */
export function useExecutionQuality(enabled = true) {
  return useQuery<ExecutionQuality | null>({
    queryKey: ["matching", "execution-quality"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/execution-quality");
      if (!r.ok) return null;
      return r.json();
    },
    refetchInterval: 60_000,
    enabled,
  });
}

/**
 * 시장가 주문을 지금 호가에 그대로 맞췄을 때의 평균 체결가와 슬리피지(최우선 호가 대비).
 * 잔량이 모자라면 shortfall에 남는 수량을 돌려준다.
 */
export function estimateMarketFill(book: OrderBookData | null | undefined, side: "BUY" | "SELL", qty: number) {
  const levels = side === "BUY" ? book?.asks : book?.bids;
  if (!levels?.length || !(qty > 0)) return null;
  let left = qty;
  let cost = 0;
  for (const l of levels) {
    const take = Math.min(left, l.quantity);
    cost += take * l.price;
    left -= take;
    if (left <= 0) break;
  }
  const filled = qty - left;
  if (filled <= 0) return null;
  const avg = cost / filled;
  const best = levels[0].price;
  const slip = side === "BUY" ? (avg / best - 1) * 100 : (1 - avg / best) * 100;
  return { avg, slip, shortfall: left };
}

export const STATUS_META: Record<string, { label: string; tone: "yellow" | "purple" | "green" | "muted" | "red" }> = {
  PENDING: { label: "예약", tone: "yellow" },
  PARTIALLY_FILLED: { label: "부분 체결", tone: "purple" },
  FILLED: { label: "체결", tone: "green" },
  CANCELLED: { label: "취소", tone: "muted" },
  REJECTED: { label: "거부", tone: "red" },
};
