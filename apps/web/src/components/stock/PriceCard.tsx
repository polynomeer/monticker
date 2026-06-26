"use client";

import { useEffect, useState } from "react";
import { Skeleton } from "@/components/ui/Skeleton";

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
      <div className="rounded-xl border border-[#44475a] bg-[#21222c] p-4">
        <Skeleton className="h-9 w-36 mb-2" />
        <Skeleton className="h-4 w-24" />
      </div>
    );
  }

  if (!data || !data.hasData) {
    return (
      <div className="rounded-xl border border-[#44475a] bg-[#21222c] p-4 text-[#6272a4] text-sm">
        시세 데이터 없음 (워커가 실행 중이어야 합니다)
      </div>
    );
  }

  return (
    <div className="rounded-xl border border-[#44475a] bg-[#21222c] p-4">
      <div className="flex items-baseline gap-3">
        <span className="text-3xl font-bold text-[#f8f8f2]">
          {data.price?.toLocaleString()}
        </span>
        <span className="text-sm text-[#6272a4]">{symbol}</span>
      </div>
      {data.volume && (
        <p className="text-sm text-[#6272a4] mt-1">
          거래량 {data.volume.toLocaleString()}
        </p>
      )}
      {data.tradeTime && (
        <p className="text-xs text-[#44475a] mt-1">
          {new Date(data.tradeTime).toLocaleTimeString("ko-KR")}
        </p>
      )}
    </div>
  );
}
