"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import type { TradeHistory } from "@/hooks/usePaperTrade";

/** /api/paper/history 한 페이지 최대 크기(서버가 1..100으로 자른다) */
const PAGE_SIZE = 100;
/** 차트 마커용으로 읽는 최대 페이지 — 최근 500건 */
export const PAPER_FILLS_MAX_PAGES = 5;

export interface PaperFills {
  fills: TradeHistory[];
  /** 최대 페이지까지 읽었는데도 더 남았을 수 있음 — 오래된 체결은 빠졌다 */
  truncated: boolean;
}

/**
 * 차트 거래 마커용 모의 체결 목록. 체결마다 따로 부르지 않고 history를 100건 단위로 한꺼번에 읽는다.
 * 키가 ["paper", ...]라 주문·초기화 뒤 usePaperTrade의 무효화로 함께 새로 읽힌다.
 */
export async function fetchPaperFills(
  fetchPage: (page: number) => Promise<TradeHistory[] | null> = defaultFetchPage,
  maxPages = PAPER_FILLS_MAX_PAGES,
): Promise<PaperFills> {
  const fills: TradeHistory[] = [];
  for (let page = 0; page < maxPages; page++) {
    const rows = await fetchPage(page);
    if (!rows) throw new Error("거래 내역 조회 실패");
    fills.push(...rows);
    if (rows.length < PAGE_SIZE) return { fills, truncated: false };
  }
  return { fills, truncated: true };
}

async function defaultFetchPage(page: number): Promise<TradeHistory[] | null> {
  const r = await authFetch(`/api/paper/history?page=${page}&size=${PAGE_SIZE}`);
  if (!r.ok) return null;
  return r.json();
}

export function usePaperFills(enabled = true) {
  return useQuery<PaperFills>({
    queryKey: ["paper", "history", "fills"],
    queryFn: () => fetchPaperFills(),
    enabled,
    staleTime: 10_000,
  });
}
