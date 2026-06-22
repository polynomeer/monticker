"use client";

import { useState, useEffect } from "react";
import Link from "next/link";

interface StockResult {
  id: number;
  symbol: string;
  name: string;
  market: string;
  sector: string | null;
  currency: string;
}

function getAccessToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("accessToken");
}

async function authFetch(url: string, options?: RequestInit): Promise<Response> {
  const token = getAccessToken();
  return fetch(url, {
    ...options,
    headers: {
      ...(options?.headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
}

export default function StockSearchPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<StockResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [addedIds, setAddedIds] = useState<Set<number>>(new Set());

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

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

  const handleAddToWatchlist = async (stock: StockResult) => {
    const groupsRes = await authFetch("/api/watchlists");
    if (!groupsRes.ok) { alert("로그인이 필요합니다."); return; }
    const groups = await groupsRes.json();
    if (!groups.length) { alert("먼저 관심종목 그룹을 만들어주세요."); return; }
    const res = await authFetch(`/api/watchlists/groups/${groups[0].id}/items`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ stockId: stock.id }),
    });
    if (res.ok) {
      setAddedIds((prev) => new Set(prev).add(stock.id));
    } else {
      alert("추가에 실패했습니다.");
    }
  };

  return (
    <div className="max-w-2xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">종목 검색</h1>

      <form onSubmit={handleSearch} className="flex gap-2 mb-6">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="종목명 또는 티커 입력"
          className="flex-1 border border-gray-300 rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="submit"
          disabled={loading}
          className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50"
        >
          {loading ? "검색 중..." : "검색"}
        </button>
      </form>

      {searched && results.length === 0 && !loading && (
        <p className="text-gray-500 text-center py-8">검색 결과가 없습니다.</p>
      )}

      <ul className="space-y-2">
        {results.map((stock) => (
          <li key={stock.id} className="flex items-center gap-2">
            <Link
              href={`/stocks/${stock.symbol}`}
              className="flex-1 flex items-center justify-between p-4 border border-gray-200 rounded-lg hover:bg-gray-50"
            >
              <div>
                <span className="font-semibold">{stock.name}</span>
                <span className="ml-2 text-sm text-gray-500">{stock.symbol}</span>
              </div>
              <div className="text-right">
                <span className="text-xs bg-gray-100 px-2 py-1 rounded">{stock.market}</span>
                {stock.sector && (
                  <p className="text-xs text-gray-400 mt-1">{stock.sector}</p>
                )}
              </div>
            </Link>
            {isLoggedIn && (
              <button
                onClick={() => handleAddToWatchlist(stock)}
                disabled={addedIds.has(stock.id)}
                className={`w-10 h-10 rounded-lg text-lg font-bold flex items-center justify-center flex-shrink-0 ${
                  addedIds.has(stock.id)
                    ? "bg-gray-100 text-gray-400 cursor-default"
                    : "bg-blue-50 text-blue-600 hover:bg-blue-100"
                }`}
                title="관심종목에 추가"
              >
                {addedIds.has(stock.id) ? "✓" : "+"}
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
