"use client";

import { useState } from "react";
import PriceCard from "./PriceCard";
import StockChart from "./StockChart";
import EventTimeline from "./EventTimeline";
import NewsPanel from "./NewsPanel";
import AlertPanel from "./AlertPanel";
import SummaryPanel from "./SummaryPanel";
import { useStockChart } from "@/hooks/useStockChart";

interface Props {
  stockId: number;
  symbol: string;
}

const INTERVALS = [
  { label: "1분", value: "1m" },
  { label: "일봉", value: "1d" },
];

export default function StockDetailClient({ stockId, symbol }: Props) {
  const [interval, setInterval] = useState("1d");
  const { candles, events, loading } = useStockChart(stockId, interval);

  return (
    <div className="grid grid-cols-1 gap-4">
      {/* Price */}
      <PriceCard symbol={symbol} />

      {/* AI Summary */}
      <SummaryPanel stockId={stockId} symbol={symbol} />

      {/* Chart */}
      <div>
        <div className="flex gap-2 mb-2">
          {INTERVALS.map((i) => (
            <button
              key={i.value}
              onClick={() => setInterval(i.value)}
              className={`text-sm px-3 py-1 rounded ${
                interval === i.value
                  ? "bg-blue-600 text-white"
                  : "bg-gray-100 text-gray-600 hover:bg-gray-200"
              }`}
            >
              {i.label}
            </button>
          ))}
        </div>
        {loading ? (
          <div className="border border-gray-200 rounded-lg h-72 flex items-center justify-center text-gray-400 animate-pulse">
            차트 로딩 중...
          </div>
        ) : (
          <StockChart candles={candles} events={events} height={300} />
        )}
      </div>

      {/* Event Timeline */}
      <EventTimeline stockId={stockId} />

      {/* News */}
      <NewsPanel stockId={stockId} />

      {/* Alert */}
      <AlertPanel stockId={stockId} symbol={symbol} />
    </div>
  );
}
