"use client";

import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import { cn } from "@/lib/utils";
import type { ScreenerItem } from "@/hooks/useScreener";

const MARKET_TABS = [
  { key: "all",      label: "전체" },
  { key: "domestic", label: "국내" },
  { key: "overseas", label: "해외" },
] as const;

function isDomestic(market: string) { return market === "KOSPI" || market === "KOSDAQ"; }

export default function SearchAutocomplete() {
  const [query, setQuery]   = useState("");
  const [results, setResults] = useState<ScreenerItem[]>([]);
  const [marketTab, setMarketTab] = useState<typeof MARKET_TABS[number]["key"]>("all");
  const [open, setOpen]     = useState(false);
  const [loading, setLoading] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const router = useRouter();
  const chartTheme = useThemeStore(s => CHART_THEMES[s.chartTheme]);

  useEffect(() => {
    if (query.length < 1) { setResults([]); setOpen(false); return; }
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(async () => {
      setLoading(true);
      try {
        const res = await fetch(`/api/screener/search?query=${encodeURIComponent(query)}&limit=20`);
        if (res.ok) {
          const data = await res.json();
          setResults(data.items ?? []);
          setOpen((data.items ?? []).length > 0);
        }
      } finally { setLoading(false); }
    }, 200);
  }, [query]);

  useEffect(() => setMarketTab("all"), [query]);

  const filtered = results
    .filter(r => marketTab === "all" || (marketTab === "domestic" ? isDomestic(r.market) : !isDomestic(r.market)))
    .slice(0, 8);

  const select = (stock: ScreenerItem) => {
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
        aria-label="종목 검색"
        placeholder="종목 검색..."
        className="border border-gray-300 dark:border-dracula-line rounded-lg px-3 py-1.5 text-sm w-48
                   bg-white dark:bg-dracula-line
                   text-gray-900 dark:text-dracula-fg
                   placeholder-gray-400 dark:placeholder-dracula-comment
                   focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-dracula-purple"
      />
      {loading && (
        <span className="absolute right-2 top-2 text-gray-400 dark:text-dracula-comment text-xs">...</span>
      )}
      {open && (
        <div className="absolute top-full left-0 mt-1 w-72
                       bg-white dark:bg-dracula-bg
                       border border-gray-200 dark:border-dracula-line
                       rounded-lg shadow-lg dark:shadow-[0_4px_24px_rgba(0,0,0,0.5)]
                       z-50 overflow-hidden">
          <div className="flex gap-1 px-2 pt-2 border-b border-gray-100 dark:border-dracula-line">
            {MARKET_TABS.map(t => (
              <button key={t.key}
                onMouseDown={e => e.preventDefault()}
                onClick={() => setMarketTab(t.key)}
                className={cn(
                  "px-2.5 py-1 text-xs font-medium rounded-t-md transition-colors",
                  marketTab === t.key
                    ? "text-blue-600 dark:text-dracula-purple border-b-2 border-blue-600 dark:border-dracula-purple"
                    : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
                )}>
                {t.label}
              </button>
            ))}
          </div>

          <ul className="max-h-72 overflow-y-auto">
            {filtered.length === 0 ? (
              <li className="px-4 py-6 text-center text-xs text-gray-400 dark:text-dracula-comment">
                해당 조건의 종목이 없습니다.
              </li>
            ) : filtered.map(s => {
              const up = s.changeRate >= 0;
              const color = up ? chartTheme.upColor : chartTheme.downColor;
              return (
                <li key={s.stockId} className="border-b border-gray-100 dark:border-dracula-line last:border-0">
                  <button
                    onMouseDown={() => select(s)}
                    className="w-full text-left px-3 py-2.5
                               hover:bg-gray-50 dark:hover:bg-dracula-line
                               flex items-center gap-2.5 transition-colors"
                  >
                    <div className="w-7 h-7 rounded-full bg-gray-100 dark:bg-dracula-line flex items-center justify-center shrink-0">
                      <span className="text-[9px] font-bold text-gray-700 dark:text-dracula-fg">{s.name.slice(0, 2)}</span>
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-gray-900 dark:text-dracula-fg truncate">{s.name}</p>
                      <p className="text-[10px] text-gray-400 dark:text-dracula-comment">{s.symbol} · {s.market}</p>
                    </div>
                    <div className="text-right shrink-0">
                      <p className="text-sm font-semibold tabular-nums text-gray-900 dark:text-dracula-fg">
                        {isDomestic(s.market) ? "₩" : "$"}{s.price.toLocaleString("ko-KR")}
                      </p>
                      <p className="text-[10px] font-medium tabular-nums" style={{ color }}>
                        {up ? "+" : ""}{s.changeRate.toFixed(2)}%
                      </p>
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
