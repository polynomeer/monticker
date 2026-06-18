"use client";

import { useEffect, useState } from "react";

interface PriceData {
  stockId: number;
  symbol: string;
  price: number;
  volume: number;
  timestamp: string;
}

export function useStockPrice(stockId: number, initialPrice?: PriceData) {
  const [price, setPrice] = useState<PriceData | null>(initialPrice ?? null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    // Fetch latest price via REST on mount
    fetch(`/api/stocks/${stockId}/price`)
      .then((r) => r.json())
      .then((data) => {
        if (data.hasData) setPrice(data);
      })
      .catch(() => {});

    // WebSocket via native (SockJS/STOMP 없이 일단 polling fallback)
    const interval = setInterval(() => {
      fetch(`/api/stocks/${stockId}/price`)
        .then((r) => r.json())
        .then((data) => {
          if (data.hasData) setPrice(data);
        })
        .catch(() => {});
    }, 3000);

    return () => clearInterval(interval);
  }, [stockId]);

  return { price, connected };
}
