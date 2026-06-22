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
      <h1 className="text-2xl font-bold mb-6 dark:text-[#eaecef]">종목 검색</h1>

      <form onSubmit={handleSearch} className="flex gap-2 mb-6">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="종목명 또는 티커 입력"
          className="flex-1 border border-gray-300 rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:bg-[#1e2329] dark:border-[#2b3240] dark:text-[#eaecef] dark:placeholder-[#848e9c]"
        />
        <button
          type="submit"
          disabled={loading}
          className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 dark:bg-[#f0b90b] dark:text-black dark:hover:bg-[#d4a20a]"
        >
          {loading ? "검색 중..." : "검색"}
        </button>
      </form>

      {searched && results.length === 0 && !loading && (
        <p className="text-gray-500 dark:text-[#848e9c] text-center py-8">검색 결과가 없습니다.</p>
      )}

      <ul className="space-y-2">
        {results.map((stock) => (
          <li key={stock.id}>
            <Link
              href={`/stocks/${stock.symbol}`}
              className="flex items-center justify-between p-4 border border-gray-200 dark:border-[#2b3240] dark:bg-[#1e2329] dark:hover:bg-[#252c3a] rounded-lg hover:bg-gray-50"
            >
              <div>
                <span className="font-semibold dark:text-[#eaecef]">{stock.name}</span>
                <span className="ml-2 text-sm text-gray-500 dark:text-[#848e9c]">{stock.symbol}</span>
              </div>
              <div className="text-right">
                <span className="text-xs bg-gray-100 dark:bg-[#252c3a] dark:text-[#848e9c] px-2 py-1 rounded">{stock.market}</span>
                {stock.sector && (
                  <p className="text-xs text-gray-400 dark:text-[#848e9c] mt-1">{stock.sector}</p>
                )}
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
