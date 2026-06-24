"use client";
import { useEffect, useState } from "react";

interface OrderBookLevel { price: number; quantity: number; amount: number; }
interface OrderBookData {
  stockId: number; symbol: string; currentPrice: number;
  asks: OrderBookLevel[]; bids: OrderBookLevel[];
}

interface Props { stockId: number; }

export default function OrderBook({ stockId }: Props) {
  const [data, setData] = useState<OrderBookData | null>(null);

  useEffect(() => {
    const fetch_ = async () => {
      const res = await fetch(`/api/stocks/${stockId}/orderbook`);
      if (res.ok) {
        const d = await res.json();
        setData(d);
      }
    };
    fetch_();
    const id = setInterval(fetch_, 1000);
    return () => clearInterval(id);
  }, [stockId]);

  if (!data) return <div className="h-40 animate-pulse bg-[#44475a]/20 rounded-lg" />;

  const maxQty = Math.max(...data.asks.map(a => a.quantity), ...data.bids.map(b => b.quantity));

  return (
    <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg overflow-hidden">
      <div className="px-4 py-2 border-b border-gray-100 dark:border-[#44475a] flex items-center justify-between">
        <span className="text-xs font-semibold text-gray-600 dark:text-[#6272a4]">호가창</span>
        <span className="text-xs font-mono font-bold dark:text-[#f8f8f2]">
          {data.currentPrice.toLocaleString()}
        </span>
      </div>

      <div className="grid grid-cols-1">
        {/* 매도 호가 — 역순으로 (높은 가격이 위) */}
        {[...data.asks].reverse().map((level, i) => (
          <div key={i} className="relative flex items-center justify-between px-3 py-0.5 text-xs">
            <div
              className="absolute right-0 top-0 bottom-0 opacity-20 bg-[#f6465d]"
              style={{ width: `${(level.quantity / maxQty) * 100}%` }}
            />
            <span className="font-mono text-[#f6465d] z-10">{level.price.toLocaleString()}</span>
            <span className="font-mono dark:text-[#6272a4] z-10">{level.quantity.toLocaleString()}</span>
          </div>
        ))}

        {/* 현재가 구분선 */}
        <div className="flex items-center justify-center py-1 bg-[#44475a]/20 border-y border-[#44475a]/40">
          <span className="text-xs font-bold font-mono dark:text-[#f8f8f2]">
            {data.currentPrice.toLocaleString()}
          </span>
        </div>

        {/* 매수 호가 */}
        {data.bids.map((level, i) => (
          <div key={i} className="relative flex items-center justify-between px-3 py-0.5 text-xs">
            <div
              className="absolute left-0 top-0 bottom-0 opacity-20 bg-[#0ecb81]"
              style={{ width: `${(level.quantity / maxQty) * 100}%` }}
            />
            <span className="font-mono text-[#0ecb81] z-10">{level.price.toLocaleString()}</span>
            <span className="font-mono dark:text-[#6272a4] z-10">{level.quantity.toLocaleString()}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
