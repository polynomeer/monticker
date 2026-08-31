"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import type { ScreenerItem } from "@/hooks/useScreener";

interface WatchlistGroup { id: number; items: { stockId: number }[]; }

export default function WatchlistTicker() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);
  const chartTheme = useThemeStore(s => CHART_THEMES[s.chartTheme]);

  const { data: groups = [] } = useQuery<WatchlistGroup[]>({
    queryKey: ["watchlist", "groups"],
    queryFn: async () => {
      const r = await authFetch("/api/watchlists");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
    staleTime: 15_000,
  });

  const stockIds = Array.from(new Set(groups.flatMap(g => g.items.map(i => i.stockId))));

  const { data: quotes } = useQuery<{ items: ScreenerItem[] }>({
    queryKey: ["watchlist", "ticker", "quotes", stockIds],
    queryFn: async () => {
      const r = await fetch(`/api/screener/quotes?ids=${stockIds.join(",")}`);
      return r.ok ? r.json() : { items: [] };
    },
    enabled: isLoggedIn && stockIds.length > 0,
    refetchInterval: 15_000,
    staleTime: 15_000,
  });

  const items = quotes?.items ?? [];
  if (!isLoggedIn || items.length === 0) return null;

  const Row = ({ ariaHidden }: { ariaHidden?: boolean }) => (
    <div className="flex items-center shrink-0" aria-hidden={ariaHidden}>
      {items.map((item, i) => {
        const up = item.changeRate >= 0;
        const color = up ? chartTheme.upColor : chartTheme.downColor;
        const isKR = item.market === "KOSPI" || item.market === "KOSDAQ";
        return (
          <Link
            key={`${ariaHidden ? "dup" : "main"}-${item.stockId}-${i}`}
            href={`/stocks/${item.symbol}`}
            tabIndex={ariaHidden ? -1 : 0}
            className="flex items-center gap-1.5 px-4 py-2 text-xs whitespace-nowrap hover:opacity-80 transition-opacity"
          >
            <span className="font-medium text-gray-700 dark:text-dracula-fg">{item.name}</span>
            <span className="font-mono text-gray-500 dark:text-dracula-comment">
              {isKR ? "₩" : "$"}{item.price.toLocaleString("ko-KR")}
            </span>
            <span className="font-mono font-semibold" style={{ color }}>
              {up ? "+" : ""}{item.changeRate.toFixed(2)}%
            </span>
            <span className="w-px h-3 bg-gray-200 dark:bg-dracula-line ml-2.5" />
          </Link>
        );
      })}
    </div>
  );

  return (
    <div className="mb-4 overflow-hidden rounded-lg border border-gray-200 dark:border-dracula-line bg-gray-50/50 dark:bg-dracula-line/5">
      <div className="flex w-max hover:[animation-play-state:paused] animate-marquee">
        <Row />
        <Row ariaHidden />
      </div>
    </div>
  );
}
