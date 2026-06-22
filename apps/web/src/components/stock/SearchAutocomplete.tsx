"use client";

import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";

interface Stock { id: number; symbol: string; name: string; market: string; }

export default function SearchAutocomplete() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Stock[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const router = useRouter();

  useEffect(() => {
    if (query.length < 1) { setResults([]); setOpen(false); return; }
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(async () => {
      setLoading(true);
      try {
        const res = await fetch(`/api/stocks/search?query=${encodeURIComponent(query)}`);
        if (res.ok) {
          const data = await res.json();
          setResults(data.slice(0, 8));
          setOpen(data.length > 0);
        }
      } finally { setLoading(false); }
    }, 200);
  }, [query]);

  const select = (stock: Stock) => {
    setQuery("");
    setOpen(false);
    router.push(`/stocks/${stock.symbol}`);
  };

  return (
    <div className="relative">
      <input
        type="text"
        value={query}
        onChange={e => setQuery(e.target.value)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        placeholder="종목 검색..."
        className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-48 focus:outline-none focus:ring-2 focus:ring-blue-500"
      />
      {loading && <span className="absolute right-2 top-2 text-gray-400 text-xs">...</span>}
      {open && (
        <ul className="absolute top-full left-0 mt-1 w-64 bg-white border border-gray-200 rounded-lg shadow-lg z-50 max-h-64 overflow-y-auto">
          {results.map(s => (
            <li key={s.id}>
              <button
                onMouseDown={() => select(s)}
                className="w-full text-left px-4 py-2 hover:bg-gray-50 flex items-center justify-between"
              >
                <span className="text-sm font-medium">{s.name}</span>
                <span className="text-xs text-gray-400">{s.symbol}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
