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

  if (loading || !stockId) return <div className="h-56 bg-gray-100 dark:bg-[#44475a] animate-pulse rounded-lg" />;

  return (
    <div>
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
    <div className="max-w-5xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">종목 비교</h1>

      <div className="flex flex-wrap gap-2 mb-6">
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => e.key === "Enter" && addSymbol()}
          placeholder="종목 코드 입력 (최대 4개)"
          className="border border-gray-300 dark:border-[#44475a] dark:bg-[#44475a] dark:text-[#f8f8f2] dark:placeholder-[#6272a4] rounded-lg px-4 py-2 text-sm w-52 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-[#bd93f9]"
        />
        <button onClick={addSymbol} className="bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] px-4 py-2 rounded-lg text-sm hover:bg-blue-700 dark:hover:bg-[#a87de8]">
          추가
        </button>
        {symbols.map(s => (
          <span key={s} className="flex items-center gap-1 bg-gray-100 dark:bg-[#44475a] px-3 py-1 rounded-full text-sm dark:text-[#f8f8f2]">
            {s}
            <button onClick={() => setSymbols(prev => prev.filter(x => x !== s))} className="text-gray-400 dark:text-[#6272a4] hover:text-gray-600 dark:hover:text-[#f8f8f2] ml-1">×</button>
          </span>
        ))}
      </div>

      <div className={`grid gap-4 ${symbols.length > 2 ? "grid-cols-2" : "grid-cols-1 md:grid-cols-2"}`}>
        {symbols.map(s => <ComparePane key={s} symbol={s} />)}
      </div>
    </div>
  );
}
