"use client";

import { useState } from "react";
import PriceCard from "./PriceCard";
import StockChart from "./chart/StockChart";
import IndicatorChart from "./IndicatorChart";
import VolumeChart from "./VolumeChart";
import EventTimeline from "./EventTimeline";
import NewsPanel from "./NewsPanel";
import AlertPanel from "./AlertPanel";
import SummaryPanel from "./SummaryPanel";
import WatchlistAddButton from "./WatchlistAddButton";
import OrderBook from "./OrderBook";
import { useStockChart } from "@/hooks/useStockChart";

interface Props { stockId: number; symbol: string; }

const INTERVALS = [
  { label: "1분", value: "1m" },
  { label: "일봉", value: "1d" },
];

export default function StockDetailClient({ stockId, symbol }: Props) {
  const [interval, setInterval] = useState("1d");
  const [showRSI,  setShowRSI]  = useState(false);
  const [showMACD, setShowMACD] = useState(false);
  const { candles, events, loading } = useStockChart(stockId, interval);

  return (
    <div className="grid grid-cols-1 gap-4">
      <PriceCard symbol={symbol} />
      <WatchlistAddButton stockId={stockId} />
      <SummaryPanel stockId={stockId} symbol={symbol} />

      {/* 차트 + 지표 토글 */}
      <div>
        <div className="flex flex-wrap items-center gap-2 mb-2">
          {INTERVALS.map(i => (
            <button key={i.value} onClick={() => setInterval(i.value)}
              className={`text-sm px-3 py-1 rounded ${
                interval === i.value
                  ? "bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-semibold"
                  : "bg-gray-100 dark:bg-[#44475a] text-gray-600 dark:text-[#6272a4]"
              }`}>{i.label}</button>
          ))}
          <div className="ml-auto flex gap-1">
            {[{ key: "RSI", state: showRSI, toggle: () => setShowRSI(p => !p) },
              { key: "MACD", state: showMACD, toggle: () => setShowMACD(p => !p) }].map(({ key, state, toggle }) => (
              <button key={key} onClick={toggle}
                className={`text-xs px-2 py-1 rounded border transition-colors ${
                  state
                    ? "border-[#bd93f9] text-[#bd93f9] bg-[#bd93f9]/10"
                    : "border-gray-200 dark:border-[#44475a] text-gray-500 dark:text-[#6272a4]"
                }`}>{key}</button>
            ))}
          </div>
        </div>

        {loading ? (
          <div className="border border-gray-200 dark:border-[#44475a] rounded-lg h-72 flex items-center justify-center text-gray-400 dark:text-[#6272a4] animate-pulse">
            차트 로딩 중...
          </div>
        ) : (
          <StockChart candles={candles} events={events} height={300} />
        )}

        {!loading && (showRSI || showMACD) && (
          <div className="mt-2">
            <IndicatorChart candles={candles} showRSI={showRSI} showMACD={showMACD} />
          </div>
        )}
      </div>

      {!loading && candles.length > 0 && <VolumeChart candles={candles} height={140} />}

      <OrderBook stockId={stockId} />
      <EventTimeline stockId={stockId} />
      <NewsPanel stockId={stockId} />
      <AlertPanel stockId={stockId} symbol={symbol} />
    </div>
  );
}
