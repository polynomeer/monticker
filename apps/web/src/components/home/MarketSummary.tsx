"use client";

import { useMarketPricesWs } from "@/hooks/useMarketPricesWs";

export default function MarketSummary() {
  const { prices, connected } = useMarketPricesWs();
  const list = Object.values(prices);

  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <div className="flex items-center justify-between mb-3">
        <h2 className="font-semibold">실시간 시세</h2>
        <span className={`text-xs px-2 py-0.5 rounded-full ${connected ? "bg-green-50 text-green-600" : "bg-gray-50 text-gray-400"}`}>
          {connected ? "● 연결됨" : "○ 연결 중"}
        </span>
      </div>
      {list.length === 0 ? (
        <p className="text-gray-400 text-sm py-2 text-center">Worker 실행 후 시세가 표시됩니다.</p>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {list.map(p => (
            <div key={p.stockId} className="border border-gray-100 rounded p-2">
              <p className="text-xs text-gray-500">{p.symbol}</p>
              <p className="font-bold text-sm">{p.price.toLocaleString()}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
