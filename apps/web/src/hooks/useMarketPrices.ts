"use client";

import { useEffect, useState } from "react";

interface PriceData {
  stockId: number;
  symbol: string;
  price: number;
  volume: number;
  timestamp: string;
  hasData: boolean;
}

type PricesMap = Record<number, PriceData>;

export function useMarketPrices(stockIds: number[]): PricesMap {
  const [prices, setPrices] = useState<PricesMap>({});

  useEffect(() => {
    if (stockIds.length === 0) return;

    const fetchPrices = async () => {
      const results = await Promise.allSettled(
        stockIds.map((id) =>
          fetch(`/api/stocks/${id}/price`)
            .then((r) => r.json())
            .then((data: PriceData) => ({ id, data }))
        )
      );
      const updated: PricesMap = {};
      for (const r of results) {
        if (r.status === "fulfilled" && r.value.data.hasData) {
          updated[r.value.id] = r.value.data;
        }
      }
      setPrices((prev) => ({ ...prev, ...updated }));
    };

    fetchPrices();
    const interval = setInterval(fetchPrices, 5000);
    return () => clearInterval(interval);
  }, [stockIds.join(",")]); // eslint-disable-line react-hooks/exhaustive-deps

  return prices;
}
