"use client";

import { useState, useEffect } from "react";
import StockChart from "@/components/stock/chart/StockChart";
import { useStockChart } from "@/hooks/useStockChart";

function ComparePane({ symbol }: { symbol: string }) {
  const [stockId, setStockId] = useState<number | null>(null);

  useEffect(() => {
    fetch(`/api/stocks/search?query=${encodeURIComponent(symbol)}`)
      .then(r => r.json())
      .then((stocks: { id: number; symbol: string }[]) => {
        const match = stocks.find(s => s.symbol === symbol);
        if (match) setStockId(match.id);
      })
      .catch(() => {});
  }, [symbol]);

  const { candles, events, loading } = useStockChart(stockId, "1d");

  if (loading || !stockId) return (
    <div className="h-56 rounded-lg bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />
  );

  return (
    <div className="animate-fade-up">
      <p className="text-sm font-semibold text-gray-600 dark:text-[#f8f8f2] mb-1">{symbol}</p>
      <StockChart candles={candles} events={events} height={220} />
    </div>
  );
}

export default function ComparePage() {
  const [symbols, setSymbols] = useState(["005930", "000660"]);
  const [input, setInput] = useState("");

  const addSymbol = () => {
    const s = input.trim().toUpperCase();
    if (s && !symbols.includes(s) && symbols.length < 4) {
      setSymbols(prev => [...prev, s]);
      setInput("");
    }
  };

  return (
    <div className="max-w-5xl mx-auto p-4 sm:p-6 animate-fade-up">
      <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-[#f8f8f2] mb-6">종목 비교</h1>

      <div className="flex flex-wrap gap-2 mb-6">
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => e.key === "Enter" && addSymbol()}
          placeholder="종목 코드 입력 (최대 4개)"
          className="border border-gray-300 dark:border-[#44475a] dark:bg-[#44475a] dark:text-[#f8f8f2] dark:placeholder-[#6272a4] rounded-lg px-4 py-2 text-sm w-52 transition-colors hover:border-gray-400 dark:hover:border-[#6272a4] focus:outline-none focus:ring-2 focus:ring-blue-500/50 dark:focus:ring-[#bd93f9]/50"
        />
        <button onClick={addSymbol} className="bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] px-4 py-2 rounded-lg text-sm font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">
          추가
        </button>
        {symbols.map(s => (
          <span key={s} className="flex items-center gap-1 bg-gray-100 dark:bg-[#44475a] px-3 py-1 rounded-full text-sm text-gray-700 dark:text-[#f8f8f2] animate-fade-up">
            {s}
            <button
              onClick={() => setSymbols(prev => prev.filter(x => x !== s))}
              aria-label={`${s} 비교에서 제거`}
              className="inline-flex items-center justify-center w-6 h-6 -mr-1.5 ml-0.5 text-gray-400 dark:text-[#6272a4] hover:text-gray-600 dark:hover:text-[#f8f8f2] transition-colors"
            >×</button>
          </span>
        ))}
      </div>

      <div className={`grid gap-4 ${symbols.length > 2 ? "grid-cols-2" : "grid-cols-1 md:grid-cols-2"}`}>
        {symbols.map(s => <ComparePane key={s} symbol={s} />)}
      </div>
    </div>
  );
}
