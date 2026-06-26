"use client";

import { useState } from "react";
import Link from "next/link";
import { PageLayout } from "@/components/ui/PageLayout";
import { Input } from "@/components/ui/Input";
import { Badge } from "@/components/ui/Badge";
import { EmptyState } from "@/components/ui/EmptyState";

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
    <PageLayout title="종목 검색" subtitle="종목명 또는 티커로 검색하세요">
      <form onSubmit={handleSearch} className="flex gap-2 mb-6 max-w-lg">
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="삼성전자, AAPL ..."
          className="flex-1"
        />
        <button
          type="submit"
          disabled={loading}
          className="px-5 py-2.5 rounded-lg bg-[#bd93f9] text-[#282a36] font-semibold text-sm hover:bg-[#ff79c6] transition-colors disabled:opacity-50"
        >
          {loading ? "..." : "검색"}
        </button>
      </form>

      {loading && (
        <ul className="space-y-2 max-w-lg">
          {[1, 2, 3].map((i) => (
            <li key={i} className="h-16 rounded-xl bg-[#44475a] animate-pulse" />
          ))}
        </ul>
      )}

      {!loading && searched && results.length === 0 && (
        <EmptyState
          icon="🔍"
          title="검색 결과가 없습니다"
          description={`"${query}"에 해당하는 종목을 찾지 못했습니다.`}
        />
      )}

      {!loading && results.length > 0 && (
        <ul className="space-y-2 max-w-lg">
          {results.map((stock) => (
            <li key={stock.id}>
              <Link
                href={`/stocks/${stock.symbol}`}
                className="flex items-center justify-between p-4 rounded-xl border border-[#44475a] bg-[#21222c] hover:border-[#bd93f9]/60 hover:bg-[#282a36] transition-colors"
              >
                <div>
                  <span className="font-semibold text-[#f8f8f2]">{stock.name}</span>
                  <span className="ml-2 text-sm text-[#6272a4]">{stock.symbol}</span>
                </div>
                <div className="flex flex-col items-end gap-1">
                  <Badge variant="neutral">{stock.market}</Badge>
                  {stock.sector && (
                    <span className="text-xs text-[#6272a4]">{stock.sector}</span>
                  )}
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </PageLayout>
  );
}
