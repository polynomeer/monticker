"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

/**
 * ADR-043 — 원장 커서 페이징.
 * 이전엔 GET /api/wallet 의 recentLedger(10건)만 보여 줬고, /api/wallet/ledger 는 전체를 한 번에 돌려줬다.
 * 이제 서버가 {items, nextCursor} 를 주고(limit ≤ 50), nextCursor 가 null 이면 마지막 페이지다.
 */
export interface LedgerEvent {
  id: number;
  eventType: string;
  amount: number;
  balanceAfter: number | null;
  paperTradeId: number | null;
  stockId: number | null;
  description: string | null;
  createdAt: string;
}

export interface LedgerPage {
  items: LedgerEvent[];
  nextCursor: number | null;
}

export const LEDGER_PAGE_SIZE = 20;

export async function fetchLedgerPage(cursor: number | null, limit = LEDGER_PAGE_SIZE): Promise<LedgerPage> {
  const qs = new URLSearchParams({ limit: String(limit) });
  if (cursor != null) qs.set("cursor", String(cursor));
  const res = await authFetch(`/api/wallet/ledger?${qs}`);
  if (!res.ok) throw new Error("원장 조회 실패");
  return res.json();
}

export function useWalletLedger(enabled = true) {
  return useInfiniteQuery<LedgerPage, Error, LedgerPage[], readonly ["wallet", "ledger"], number | null>({
    queryKey: ["wallet", "ledger"] as const,
    queryFn: ({ pageParam }) => fetchLedgerPage(pageParam),
    initialPageParam: null,
    getNextPageParam: last => last.nextCursor,
    select: data => data.pages,
    enabled,
  });
}
