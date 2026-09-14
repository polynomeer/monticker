"use client";

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

/**
 * ADR-039 — 시장 요약 (1초 1회).
 *
 * 이전 useMarketPricesWs는 /topic/market(전역 브로드캐스트)을 구독해 "모든 종목의 모든 틱"을 받고
 * 클라이언트에서 최근 12개를 잘라 썼다 — 표시되는 종목이 도착 순서라 비결정적이었고, 서버는
 * 구독자 수 × 전체 틱 레이트를 곱해 보내야 했다(L-02 기준선: 500 연결에서 50,000 msg/s).
 * 이제 worker가 서버에서 상위 종목·상승/하락 집계를 계산해 /topic/market/summary 로 1 msg/s 보낸다.
 * 초기 렌더·재연결 사이는 GET /api/market/summary(Redis, TTL 5s)로 메운다.
 */
export interface SummaryItem {
  stockId: number;
  symbol: string;
  name: string;
  market: string;
  price: number;
  changeRate: number | null;
  volume: number;
  amount: number;
}

export interface MarketSummary {
  asOf: string;
  stockCount: number;
  advancers: number;
  decliners: number;
  unchanged: number;
  topByAmount: SummaryItem[];
  topGainers: SummaryItem[];
  topLosers: SummaryItem[];
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function useMarketSummaryWs() {
  const [summary, setSummary] = useState<MarketSummary | null>(null);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    // 초기값: REST 폴백 (204면 아직 요약이 없다)
    fetch(`${API_BASE}/api/market/summary`)
      .then(r => (r.status === 200 ? r.json() : null))
      .then(data => { if (data) setSummary(data); })
      .catch(() => {});

    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe("/topic/market/summary", msg => {
          try { setSummary(JSON.parse(msg.body)); } catch { /* ignore malformed */ }
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError:  () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, []);

  return { summary, connected };
}
