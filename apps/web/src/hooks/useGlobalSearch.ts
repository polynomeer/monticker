"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

/** 서버 전략 검색어 최대 길이(StrategyMarketController.MAX_SEARCH_QUERY_LENGTH) */
export const STRATEGY_QUERY_MAX = 50;

export interface StockResult { id: number; symbol: string; name: string; market: string; sector: string | null; currency: string; }
export interface EventResult { id: number; stockId: number; eventType: string; title: string; eventTime: string; }
export interface NewsResult { id: number; stockId: number | null; title: string; url: string; source: string | null; publishedAt: string; }
export interface StrategyRow { id: number; name: string; description: string | null; price: number | null; subscribe_count: number | null; author_nickname?: string | null; }

/** 입력을 늦춰 쓴다 — 키 입력마다 검색 API를 부르지 않도록. */
export function useDebounced<T>(v: T, ms = 200) {
  const [d, setD] = useState(v);
  useEffect(() => {
    const t = setTimeout(() => setD(v), ms);
    return () => clearTimeout(t);
  }, [v, ms]);
  return d;
}

/**
 * 검색어별 GET — react-query가 늦게 도착한 이전 검색어 응답을 키로 분리해 최신 결과를 덮어쓰지 않게 한다.
 * authFetch — 전략 검색(/api/quant/market/**)은 로그인이 필요한데 토큰은 쿠키가 아니라 헤더로 가서, 일반 fetch로는
 * 로그인한 사용자도 늘 빈 결과였다. 토큰이 없으면 헤더 없이 보내므로 종목·이벤트 검색(공개)은 그대로다.
 */
export function useSearch<T>(key: string, url: string | null) {
  return useQuery<T[]>({
    queryKey: ["search", key, url],
    queryFn: async ({ signal }) => {
      const r = await authFetch(url!, { signal });
      if (!r.ok) return [];
      const j = await r.json();
      return Array.isArray(j) ? j : (j?.items ?? j?.content ?? []);
    },
    enabled: !!url,
    staleTime: 30_000,
  });
}

/**
 * ADR-112 — 상단 고정 검색창의 미리보기. 종목·이벤트·전략을 함께 부른다(통합 검색 API가 없다).
 * 검색 화면(`/stocks/search`)과 같은 쿼리 키라 "전체 결과"로 넘어가도 다시 부르지 않는다.
 */
export function useGlobalSearch(query: string, limits = { stocks: 5, events: 3, strategies: 3 }) {
  const q = useDebounced(query.trim());
  const enc = encodeURIComponent(q);
  const stocks = useSearch<StockResult>("stocks", q ? `/api/stocks/search?query=${enc}` : null);
  const events = useSearch<EventResult>("events", q ? `/api/events/search?query=${enc}&limit=12` : null);
  const term = q.slice(0, STRATEGY_QUERY_MAX);
  const strategies = useSearch<StrategyRow>("strategies", term ? `/api/quant/market/search?q=${encodeURIComponent(term)}&page=0&size=8` : null);
  return {
    q,
    stocks: (stocks.data ?? []).slice(0, limits.stocks),
    events: (events.data ?? []).slice(0, limits.events),
    strategies: (strategies.data ?? []).slice(0, limits.strategies),
    loading: stocks.isFetching || events.isFetching || strategies.isFetching,
  };
}
