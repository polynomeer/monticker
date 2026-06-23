"use client";

import { useMarketPricesWs } from "@/hooks/useMarketPricesWs";

export default function MarketSummary() {
  const { prices, connected } = useMarketPricesWs();
  const list = Object.values(prices);

  return (
    <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg p-4">
      <div className="flex items-center justify-between mb-3">
        <h2 className="font-semibold dark:text-[#f8f8f2]">실시간 시세</h2>
        <span className={`text-xs px-2 py-0.5 rounded-full ${connected ? "bg-green-50 dark:bg-[#0ecb81]/15 text-green-600 dark:text-[#0ecb81]" : "bg-gray-50 dark:bg-[#44475a] text-gray-400 dark:text-[#6272a4]"}`}>
          {connected ? "● 연결됨" : "○ 연결 중"}
        </span>
      </div>
      {list.length === 0 ? (
        <p className="text-gray-400 dark:text-[#6272a4] text-sm py-2 text-center">Worker 실행 후 시세가 표시됩니다.</p>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {list.map(p => (
            <div key={p.stockId} className="border border-gray-100 dark:border-[#44475a] dark:bg-[#44475a] rounded p-2">
              <p className="text-xs text-gray-500 dark:text-[#6272a4]">{p.symbol}</p>
              <p className="font-bold text-sm dark:text-[#f8f8f2]">{p.price.toLocaleString()}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
