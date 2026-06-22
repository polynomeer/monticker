"use client";

import { useEffect, useState } from "react";

interface PriceData {
  stockId: number;
  symbol: string;
  price: number | null;
  volume: number | null;
  tradeTime: string | null;
  hasData: boolean;
}

interface Props {
  symbol: string;
}

export default function PriceCard({ symbol }: Props) {
  const [data, setData] = useState<PriceData | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchPrice = async (stockId: number) => {
    const res = await fetch(`/api/stocks/${stockId}/price`);
    if (res.ok) setData(await res.json());
  };

  useEffect(() => {
    // Resolve symbol → stockId via search
    fetch(`/api/stocks/search?query=${encodeURIComponent(symbol)}`)
      .then((r) => r.json())
      .then((stocks: { id: number; symbol: string }[]) => {
        const match = stocks.find((s) => s.symbol === symbol);
        if (match) {
          fetchPrice(match.id).finally(() => setLoading(false));
          const interval = setInterval(() => fetchPrice(match.id), 3000);
          return () => clearInterval(interval);
        } else {
          setLoading(false);
        }
      })
      .catch(() => setLoading(false));
  }, [symbol]);

  if (loading) {
    return (
      <div className="border border-gray-200 dark:border-[#2b3240] dark:bg-[#1e2329] rounded-lg p-4 animate-pulse">
        <div className="h-8 bg-gray-100 rounded w-32 mb-2" />
        <div className="h-4 bg-gray-100 rounded w-24" />
      </div>
    );
  }

  if (!data || !data.hasData) {
    return (
      <div className="border border-gray-200 dark:border-[#2b3240] dark:bg-[#1e2329] rounded-lg p-4 text-gray-400 dark:text-[#848e9c]">
        시세 데이터 없음 (워커가 실행 중이어야 합니다)
      </div>
    );
  }

  return (
    <div className="border border-gray-200 dark:border-[#2b3240] dark:bg-[#1e2329] rounded-lg p-4">
      <div className="flex items-baseline gap-3">
        <span className="text-3xl font-bold dark:text-[#eaecef]">
          {data.price?.toLocaleString()}
        </span>
        <span className="text-sm text-gray-500 dark:text-[#848e9c]">{symbol}</span>
      </div>
      {data.volume && (
        <p className="text-sm text-gray-500 dark:text-[#848e9c] mt-1">
          거래량 {data.volume.toLocaleString()}
        </p>
      )}
      {data.tradeTime && (
        <p className="text-xs text-gray-400 dark:text-[#848e9c] mt-1">
          {new Date(data.tradeTime).toLocaleTimeString("ko-KR")}
        </p>
      )}
    </div>
  );
}
