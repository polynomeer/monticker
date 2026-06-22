"use client";

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

interface MarketPrice {
  stockId: number;
  symbol: string;
  price: number;
  volume: number;
  timestamp: string;
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function useMarketPricesWs() {
  const [prices, setPrices] = useState<Record<number, MarketPrice>>({});
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe("/topic/market", msg => {
          try {
            const data: MarketPrice = JSON.parse(msg.body);
            setPrices(prev => ({ ...prev, [data.stockId]: data }));
          } catch { /* ignore */ }
        });
      },
      onDisconnect: () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, []);

  return { prices, connected };
}
