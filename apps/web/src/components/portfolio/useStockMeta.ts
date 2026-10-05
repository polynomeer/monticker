"use client";

import { useMemo } from "react";
import { useQueries } from "@tanstack/react-query";

/** GET /api/stocks/{id} 의 표시용 필드 — 섹터·이름은 거의 안 바뀌므로 오래 캐시한다. */
export interface StockMeta {
  id: number;
  symbol: string;
  name: string;
  market?: string;
  sector: string | null;
}

async function fetchStockMeta(id: number): Promise<StockMeta | null> {
  try {
    const r = await fetch(`/api/stocks/${id}`);
    if (!r.ok) return null;
    const s = await r.json();
    return { id: s.id, symbol: s.symbol, name: s.name, market: s.market, sector: s.sector ?? null };
  } catch {
    return null;
  }
}

/**
 * 여러 종목의 이름·섹터를 한 번에 채운다. 정산·주문·보유 응답은 stockId만 들고 있는 경우가 많다.
 * 같은 id는 TanStack Query가 한 번만 요청한다.
 */
export function useStockMeta(ids: (number | null | undefined)[]) {
  const unique = useMemo(() => [...new Set(ids.filter((v): v is number => typeof v === "number" && v > 0))].sort((a, b) => a - b), [ids]);
  const results = useQueries({
    queries: unique.map((id) => ({
      queryKey: ["stocks", id, "meta"] as const,
      queryFn: () => fetchStockMeta(id),
      staleTime: 10 * 60_000,
    })),
  });
  const key = results.map((r) => (r.data ? r.data.id : "")).join(",");
  return useMemo(() => {
    const map = new Map<number, StockMeta>();
    results.forEach((r) => { if (r.data) map.set(r.data.id, r.data); });
    return map;
    // results 배열은 매 렌더 새로 만들어지므로 내용(key)으로 메모한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
}
