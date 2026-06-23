"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

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
  wsConnected: boolean;
}

interface WsMessage {
  stockId: number;
  price: number;
  volume: number;
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function useScreener(tab: string, market: string, sort: string) {
  const [state, setState] = useState<ScreenerState>({
    items: [], total: 0, hasMore: false,
    loading: true, loadingMore: false, wsConnected: false,
  });

  // prevClose 캐시 — 랭킹 로드 시 저장, 가격 패치 시 등락률 재계산에 사용
  const prevCloseRef = useRef<Record<number, number>>({});
  const offsetRef    = useRef(0);
  const abortRef     = useRef<AbortController | null>(null);
  const wsClientRef  = useRef<Client | null>(null);

  // ── REST: 랭킹 로드 (10초 주기) ───────────────────────────────
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

      // prevClose 캐시 업데이트
      data.items.forEach((item: ScreenerItem & { prevClose?: number }) => {
        if (item.price && item.changeRate !== undefined) {
          const prevClose = item.changeRate !== 0
            ? item.price / (1 + item.changeRate / 100)
            : item.price;
          prevCloseRef.current[item.stockId] = prevClose;
        }
      });

      setState(p => ({
        ...p,
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

  useEffect(() => {
    offsetRef.current = 0;
    fetch_(true);
    const id = setInterval(() => fetch_(true), 10_000);
    return () => { clearInterval(id); abortRef.current?.abort(); };
  }, [fetch_]);

  // ── WebSocket: 가격만 1초 실시간 패치 ─────────────────────────
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        setState(p => ({ ...p, wsConnected: true }));

        client.subscribe("/topic/market", (msg) => {
          try {
            const data: WsMessage = JSON.parse(msg.body);

            setState(p => {
              const prevClose = prevCloseRef.current[data.stockId];
              const changeRate = prevClose && prevClose > 0
                ? ((data.price - prevClose) / prevClose) * 100
                : 0;
              const changeAmount = prevClose ? data.price - prevClose : 0;

              const updated = p.items.map(item =>
                item.stockId === data.stockId
                  ? { ...item, price: data.price, changeRate, changeAmount, volume: data.volume }
                  : item
              );

              // 변경이 없으면 동일 참조 반환 (리렌더 방지)
              return updated === p.items ? p : { ...p, items: updated };
            });
          } catch { /* ignore malformed */ }
        });
      },
      onDisconnect: () => setState(p => ({ ...p, wsConnected: false })),
    });

    client.activate();
    wsClientRef.current = client;

    return () => {
      client.deactivate();
      wsClientRef.current = null;
    };
  }, []); // WS는 마운트 시 한 번만 연결

  const loadMore = useCallback(() => {
    if (!state.hasMore || state.loadingMore) return;
    fetch_(false);
  }, [state.hasMore, state.loadingMore, fetch_]);

  return { ...state, loadMore };
}
