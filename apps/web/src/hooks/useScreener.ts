"use client";

import { useState, useEffect, useCallback, useRef } from "react";

export interface ScreenerItem {
  rank: number;
  stockId: number;
  symbol: string;
  name: string;
  market: string;
  sector: string | null;
  price: number;
  changeRate: number;
  changeAmount: number;
  volume: number;
  amount: number;
  buyRatio: number;
  sellRatio: number;
}

interface ScreenerState {
  items: ScreenerItem[];
  total: number;
  hasMore: boolean;
  loading: boolean;
  loadingMore: boolean;
}

export function useScreener(tab: string, market: string, sort: string) {
  const [state, setState] = useState<ScreenerState>({
    items: [], total: 0, hasMore: false, loading: true, loadingMore: false,
  });
  const offsetRef = useRef(0);
  const abortRef  = useRef<AbortController | null>(null);

  const fetch_ = useCallback(async (reset = false) => {
    abortRef.current?.abort();
    abortRef.current = new AbortController();

    const offset = reset ? 0 : offsetRef.current;
    setState(p => ({ ...p, loading: reset, loadingMore: !reset }));

    try {
      const res = await fetch(
        `/api/screener?tab=${tab}&market=${market}&sort=${sort}&limit=20&offset=${offset}`,
        { signal: abortRef.current.signal }
      );
      if (!res.ok) return;
      const data = await res.json();
      setState(p => ({
        items:       reset ? data.items : [...p.items, ...data.items],
        total:       data.total,
        hasMore:     data.hasMore,
        loading:     false,
        loadingMore: false,
      }));
      offsetRef.current = offset + data.items.length;
    } catch (e: unknown) {
      if ((e as Error).name !== "AbortError")
        setState(p => ({ ...p, loading: false, loadingMore: false }));
    }
  }, [tab, market, sort]);

  // 필터 변경 시 리셋
  useEffect(() => {
    offsetRef.current = 0;
    fetch_(true);
    const id = setInterval(() => fetch_(true), 10_000);
    return () => { clearInterval(id); abortRef.current?.abort(); };
  }, [fetch_]);

  const loadMore = useCallback(() => {
    if (!state.hasMore || state.loadingMore) return;
    fetch_(false);
  }, [state.hasMore, state.loadingMore, fetch_]);

  return { ...state, loadMore };
}
