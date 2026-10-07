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

  const box = "flex flex-col gap-1 rounded-[10px] bg-tm-inner p-3.5";

  if (loading) {
    return (
      <div className={box} aria-busy="true" aria-label="시세 불러오는 중">
        <div className="h-8 w-36 animate-pulse rounded bg-tm-raised" />
        <div className="h-4 w-24 animate-pulse rounded bg-tm-raised" />
      </div>
    );
  }

  if (!data || !data.hasData) {
    return <div className={`${box} text-13 text-tm-muted`}>시세 데이터 없음 (워커가 실행 중이어야 합니다)</div>;
  }

  return (
    <div className={box}>
      <div className="flex items-baseline gap-3">
        <span className="num text-[1.75rem] font-semibold text-dracula-fg">{data.price?.toLocaleString()}</span>
        <span className="num text-13 text-tm-muted">{symbol}</span>
      </div>
      {data.volume && <p className="num m-0 text-13 text-tm-muted">거래량 {data.volume.toLocaleString()}</p>}
      {data.tradeTime && <p className="num m-0 text-2xs text-tm-muted">{new Date(data.tradeTime).toLocaleTimeString("ko-KR")}</p>}
    </div>
  );
}
