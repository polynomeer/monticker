"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import type { TradeHistory } from "@/hooks/usePaperTrade";

/** /api/paper/history 한 페이지 최대 크기(서버가 1..100으로 자른다) */
const PAGE_SIZE = 100;
/** 한 종목·차트 구간에서 마커용으로 읽는 최대 페이지 — 최근 2,000건 */
export const PAPER_FILLS_MAX_PAGES = 20;
export const PAPER_FILLS_MAX = PAGE_SIZE * PAPER_FILLS_MAX_PAGES;

export interface PaperFillsQuery {
  stockId: number;
  /** 차트 구간 시작(포함, epoch seconds). 이보다 앞선 체결은 받지 않고 있었는지만 알린다. */
  fromSec: number;
}

export interface PaperFills {
  /** 이 종목·구간의 체결(최신순) */
  fills: TradeHistory[];
  /** 최대 페이지까지 읽었는데도 더 남았을 수 있음 — 구간 안의 오래된 체결이 빠졌다 */
  truncated: boolean;
  /** 차트 구간보다 앞선 체결이 하나라도 있는지 */
  hasEarlier: boolean;
}

type FetchPage = (params: URLSearchParams) => Promise<TradeHistory[] | null>;

/** 서버 필터(stockId·from·to)를 담은 history 쿼리 문자열. from은 포함, to는 제외 경계다. */
export function historyParams(q: { stockId: number; page: number; size: number; from?: number; to?: number }): URLSearchParams {
  const p = new URLSearchParams({ stockId: String(q.stockId), page: String(q.page), size: String(q.size) });
  if (q.from != null) p.set("from", new Date(q.from * 1000).toISOString());
  if (q.to != null) p.set("to", new Date(q.to * 1000).toISOString());
  return p;
}

/**
 * 차트 거래 마커용 모의 체결 목록 — 서버에서 선택한 종목·차트 구간만 100건 단위로 읽는다(체결마다 따로 부르지 않는다).
 * 구간 이전 체결이 있는지는 1건짜리 요청 하나로만 확인한다.
 */
export async function fetchPaperFills(
  { stockId, fromSec }: PaperFillsQuery,
  fetchPage: FetchPage = defaultFetchPage,
  maxPages = PAPER_FILLS_MAX_PAGES,
): Promise<PaperFills> {
  // 본 목록과 나란히 보낸다. 본 목록이 먼저 실패해도 처리되지 않은 거부가 남지 않게 null로 접는다.
  const earlier = fetchPage(historyParams({ stockId, page: 0, size: 1, to: fromSec })).catch(() => null);
  const fills: TradeHistory[] = [];
  let truncated = true;
  for (let page = 0; page < maxPages; page++) {
    const rows = await fetchPage(historyParams({ stockId, page, size: PAGE_SIZE, from: fromSec }));
    if (!rows) throw new Error("거래 내역 조회 실패");
    fills.push(...rows);
    if (rows.length < PAGE_SIZE) {
      truncated = false;
      break;
    }
  }
  const before = await earlier;
  if (!before) throw new Error("거래 내역 조회 실패");
  return { fills, truncated, hasEarlier: before.length > 0 };
}

async function defaultFetchPage(params: URLSearchParams): Promise<TradeHistory[] | null> {
  const r = await authFetch(`/api/paper/history?${params}`);
  if (!r.ok) return null;
  return r.json();
}

/** query가 없으면(종목 미선택·봉 미로딩) 부르지 않는다. 키가 ["paper", ...]라 주문·초기화 뒤 함께 새로 읽힌다. */
export function usePaperFills(query: PaperFillsQuery | null) {
  return useQuery<PaperFills>({
    queryKey: ["paper", "history", "fills", query?.stockId ?? null, query?.fromSec ?? null],
    queryFn: () => fetchPaperFills(query!),
    enabled: query != null,
    staleTime: 10_000,
  });
}
