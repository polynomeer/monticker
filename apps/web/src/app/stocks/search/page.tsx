"use client";

import { useState } from "react";
import Link from "next/link";

interface StockResult {
  id: number;
  symbol: string;
  name: string;
  market: string;
  sector: string | null;
  currency: string;
}

export default function StockSearchPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<StockResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;

    setLoading(true);
    setSearched(true);
    try {
      const res = await fetch(`/api/stocks/search?query=${encodeURIComponent(query)}`);
      if (res.ok) {
        const data = await res.json();
        setResults(data);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6 dark:text-[#f8f8f2]">종목 검색</h1>

      <form onSubmit={handleSearch} className="flex gap-2 mb-6">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="종목명 또는 티커 입력"
          className="flex-1 border border-gray-300 rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:bg-[#282a36] dark:border-[#44475a] dark:text-[#f8f8f2] dark:placeholder-[#6272a4]"
        />
        <button
          type="submit"
          disabled={loading}
          className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 dark:bg-[#bd93f9] dark:text-black dark:hover:bg-[#a87de8]"
        >
          {loading ? "검색 중..." : "검색"}
        </button>
      </form>

      {searched && results.length === 0 && !loading && (
        <p className="text-gray-500 dark:text-[#6272a4] text-center py-8">검색 결과가 없습니다.</p>
      )}

      <ul className="space-y-2">
        {results.map((stock) => (
          <li key={stock.id}>
            <Link
              href={`/stocks/${stock.symbol}`}
              className="flex items-center justify-between p-4 border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] dark:hover:bg-[#44475a] rounded-lg hover:bg-gray-50"
            >
              <div>
                <span className="font-semibold dark:text-[#f8f8f2]">{stock.name}</span>
                <span className="ml-2 text-sm text-gray-500 dark:text-[#6272a4]">{stock.symbol}</span>
              </div>
              <div className="text-right">
                <span className="text-xs bg-gray-100 dark:bg-[#44475a] dark:text-[#6272a4] px-2 py-1 rounded">{stock.market}</span>
                {stock.sector && (
                  <p className="text-xs text-gray-400 dark:text-[#6272a4] mt-1">{stock.sector}</p>
                )}
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
