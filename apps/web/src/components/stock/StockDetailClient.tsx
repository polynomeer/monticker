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
import StockTradeButton from "@/components/paper/StockTradeButton";
import { useStockChart } from "@/hooks/useStockChart";
import { useVwap } from "@/hooks/useVwap";
import { useStockPrice } from "@/hooks/useStockPrice";

interface Props { stockId: number; symbol: string; stockName: string; }

const INTERVALS = [
  { label: "1분", value: "1m" },
  { label: "일봉", value: "1d" },
  { label: "1주", value: "1w" },
  { label: "1달", value: "1M" },
  { label: "3달", value: "3M" },
  { label: "1년", value: "1Y" },
];

export default function StockDetailClient({ stockId, symbol, stockName }: Props) {
  const [interval, setInterval] = useState("1d");
  const [showRSI,  setShowRSI]  = useState(false);
  const [showMACD, setShowMACD] = useState(false);
  const [showVwap, setShowVwap] = useState(false);
  const { candles, events, loading } = useStockChart(stockId, interval);
  const { data: vwapData } = useVwap(stockId);
  const { price } = useStockPrice(stockId);

  return (
    <div className="grid grid-cols-1 gap-4 animate-fade-up">
      <PriceCard symbol={symbol} />
      <WatchlistAddButton stockId={stockId} />
      <StockTradeButton
        stockId={stockId}
        symbol={symbol}
        name={stockName}
        currentPrice={price?.price ?? 0}
      />
      <SummaryPanel stockId={stockId} symbol={symbol} />

      <div>
        <div className="flex flex-wrap items-center gap-2 mb-2">
          {INTERVALS.map(i => (
            <button key={i.value} onClick={() => setInterval(i.value)}
              className={`text-sm px-3 py-1 rounded transition-all duration-150 active:scale-95 ${
                interval === i.value
                  ? "bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-semibold"
                  : "bg-gray-100 dark:bg-dracula-line text-gray-600 dark:text-dracula-comment hover:bg-gray-200 dark:hover:bg-dracula-line/70"
              }`}>{i.label}</button>
          ))}
          <div className="ml-auto flex gap-1">
            {[
              { key: "RSI",  state: showRSI,  toggle: () => setShowRSI(p => !p) },
              { key: "MACD", state: showMACD, toggle: () => setShowMACD(p => !p) },
              { key: "VWAP", state: showVwap, toggle: () => setShowVwap(p => !p) },
            ].map(({ key, state, toggle }) => (
              <button key={key} onClick={toggle}
                className={`text-xs px-2 py-1 rounded border transition-all duration-150 active:scale-95 ${
                  state
                    ? "border-dracula-purple text-dracula-purple bg-dracula-purple/10"
                    : "border-gray-200 dark:border-dracula-line text-gray-500 dark:text-dracula-comment hover:border-gray-300 dark:hover:border-dracula-comment"
                }`}>{key}</button>
            ))}
          </div>
        </div>

        {loading ? (
          <div className="border border-gray-200 dark:border-dracula-line rounded-lg h-72 flex items-center justify-center text-gray-400 dark:text-dracula-comment animate-pulse">
            차트 로딩 중...
          </div>
        ) : (
          <StockChart
            candles={candles}
            events={events}
            height={300}
            vwapData={showVwap ? vwapData : undefined}
          />
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
