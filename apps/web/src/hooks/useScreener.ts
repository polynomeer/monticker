"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { Client, type StompSubscription } from "@stomp/stompjs";
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
  marketCap: number | null;
  per: number | null;
  pbr: number | null;
  isFundamentalsMocked: boolean;
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

export function useScreener(tab: string, market: string, sort: string, marketCapTier: string = "all") {
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
        `/api/screener?tab=${tab}&market=${market}&sort=${sort}&limit=20&offset=${offset}&marketCapTier=${marketCapTier}`,
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
  }, [tab, market, sort, marketCapTier]);

  useEffect(() => {
    offsetRef.current = 0;
    fetch_(true);
    const id = setInterval(() => fetch_(true), 10_000);
    return () => { clearInterval(id); abortRef.current?.abort(); };
  }, [fetch_]);

  // ── WebSocket: 로드된 종목만 개별 구독 (ADR-039) ──────────────
  // 이전엔 /topic/market(전역)을 구독해 유니버스 전체의 틱을 받고 목록에 있는 것만 골라 썼다 —
  // 받은 것의 99%를 버렸고, 서버는 구독자 수 × 전체 틱 레이트를 보내야 했다(L-02: 500 연결에서 50,000 msg/s).
  // 이제 목록에 있는 종목의 /topic/stocks/{id}만 구독하고, 목록이 바뀌면 차집합만 구독/해제한다.
  // 후속: TanStack Virtual의 가시 행만 구독(§6.1.3) — 지금은 "로드된 페이지"(≤ 수십 개) 단위다.
  const subsRef = useRef<Map<number, StompSubscription>>(new Map());
  const applyTick = useCallback((data: WsMessage) => {
    setState(p => {
      let changed = false;
      const updated = p.items.map(item => {
        if (item.stockId !== data.stockId || item.price === data.price) return item;
        changed = true;
        const prevClose = prevCloseRef.current[data.stockId];
        const changeRate = prevClose && prevClose > 0 ? ((data.price - prevClose) / prevClose) * 100 : 0;
        const changeAmount = prevClose ? data.price - prevClose : 0;
        return { ...item, price: data.price, changeRate, changeAmount, volume: data.volume };
      });
      // 실제로 바뀐 게 없으면 같은 상태를 돌려 리렌더를 막는다 (이전 코드의 참조 비교는 map이 항상 새 배열이라 동작하지 않았다)
      return changed ? { ...p, items: updated } : p;
    });
  }, []);

  useEffect(() => {
    const subs = subsRef.current;   // cleanup에서 최신 ref 대신 이 인스턴스를 쓴다 (react-hooks/exhaustive-deps)
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      reconnectDelay: 5000,
      onConnect: () => setState(p => ({ ...p, wsConnected: true })),
      onDisconnect: () => { subs.clear(); setState(p => ({ ...p, wsConnected: false })); },
    });
    client.activate();
    wsClientRef.current = client;
    return () => {
      subs.clear();
      client.deactivate();
      wsClientRef.current = null;
    };
  }, []); // WS 연결은 마운트 시 한 번만

  // 목록·연결 상태가 바뀔 때 구독 차집합을 적용한다. 빠른 스크롤/정렬 변경으로 SUBSCRIBE/UNSUBSCRIBE
  // 프레임이 폭주하지 않도록 200ms 디바운스.
  const loadedIds = state.items.map(i => i.stockId).join(",");
  useEffect(() => {
    if (!state.wsConnected) return;
    const timer = setTimeout(() => {
      const client = wsClientRef.current;
      if (!client?.connected) return;
      const wanted = new Set(state.items.map(i => i.stockId));
      const subs = subsRef.current;
      for (const [id, sub] of subs) if (!wanted.has(id)) { sub.unsubscribe(); subs.delete(id); }
      for (const id of wanted) if (!subs.has(id)) {
        subs.set(id, client.subscribe(`/topic/stocks/${id}`, msg => {
          try { applyTick(JSON.parse(msg.body)); } catch { /* ignore malformed */ }
        }));
      }
    }, 200);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadedIds, state.wsConnected, applyTick]);

  const loadMore = useCallback(() => {
    if (!state.hasMore || state.loadingMore) return;
    fetch_(false);
  }, [state.hasMore, state.loadingMore, fetch_]);

  return { ...state, loadMore };
}
