"use client";

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

interface PriceData {
  stockId: number;
  symbol: string;
  price: number;
  volume: number;
  timestamp: string;
}

type ConnectionState = "connecting" | "connected" | "disconnected";

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function useStockPrice(stockId: number) {
  const [price, setPrice] = useState<PriceData | null>(null);
  const [state, setState] = useState<ConnectionState>("connecting");
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    // REST로 최신가 즉시 표시
    fetch(`/api/stocks/${stockId}/price`)
      .then(r => r.json())
      .then(data => { if (data?.hasData) setPrice(data); })
      .catch(() => {});

    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        setState("connected");
        client.subscribe(`/topic/stocks/${stockId}`, msg => {
          try {
            const data = JSON.parse(msg.body);
            setPrice({
              stockId: data.stockId,
              symbol:  data.symbol,
              price:   data.price,
              volume:  data.volume,
              timestamp: data.timestamp,
            });
          } catch {/* ignore malformed */}
        });
      },
      onDisconnect: () => setState("disconnected"),
      onStompError:  () => setState("disconnected"),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [stockId]);

  return { price, state };
}
