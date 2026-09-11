"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

interface MoverEvent {
  stockId: number;
  eventType: string;
  title: string;
  importanceScore: number;
  eventTime: string;
}

interface QuoteItem { stockId: number; symbol: string; }

interface Mover {
  stockId: number;
  title: string;
  topScore: number;
  eventCount: number;
  latestType: string;
}

const COLOR: Record<string, string> = {
  PRICE_SPIKE:  "text-market-up bg-green-50 dark:bg-market-up/15",
  PRICE_DROP:   "text-market-down bg-red-50 dark:bg-market-down/15",
  VOLUME_SURGE: "text-blue-600 dark:text-dracula-yellow bg-blue-50 dark:bg-dracula-yellow/15",
};
const LABEL: Record<string, string> = {
  PRICE_SPIKE: "급등", PRICE_DROP: "급락", VOLUME_SURGE: "급증",
};

export default function TopMovers() {
  // RecentEvents/WatchlistSummary와 같은 queryKey+fetcher — react-query가 요청을 합쳐준다.
  const { data: events = [] } = useQuery<MoverEvent[]>({
    queryKey: ["events", "recent", "home"],
    queryFn:  async () => {
      const r = await fetch("/api/events/recent?limit=50");
      return r.ok ? r.json() : [];
    },
    refetchInterval: 10_000,
    staleTime:       10_000,
  });

  const movers: Mover[] = (() => {
    const map = new Map<number, Mover>();
    for (const e of events) {
      if (!["PRICE_SPIKE", "PRICE_DROP", "VOLUME_SURGE"].includes(e.eventType)) continue;
      const existing = map.get(e.stockId);
      if (!existing) {
        map.set(e.stockId, {
          stockId: e.stockId,
          title: e.title,
          topScore: e.importanceScore,
          eventCount: 1,
          latestType: e.eventType,
        });
      } else {
        existing.eventCount++;
        if (e.importanceScore > existing.topScore) {
          existing.topScore = e.importanceScore;
          existing.latestType = e.eventType;
        }
      }
    }
    return [...map.values()]
      .sort((a, b) => b.topScore - a.topScore || b.eventCount - a.eventCount)
      .slice(0, 5);
  })();

  const stockIds = movers.map(m => m.stockId);
  const { data: quotes = [] } = useQuery<QuoteItem[]>({
    queryKey: ["screener", "quotes", "home-movers", stockIds],
    queryFn:  async () => {
      const r = await fetch(`/api/screener/quotes?ids=${stockIds.join(",")}`);
      if (!r.ok) return [];
      const body = await r.json();
      return body.items ?? [];
    },
    enabled:   stockIds.length > 0,
    staleTime: 30_000,
  });
  const symbolByStockId = Object.fromEntries(quotes.map(q => [q.stockId, q.symbol]));

  if (movers.length === 0) return null;

  return (
    <div className="border border-gray-200 dark:border-dracula-line dark:bg-dracula-bg rounded-lg p-4">
      <h2 className="font-semibold dark:text-dracula-fg mb-3">주목 종목</h2>
      <ul className="space-y-2">
        {movers.map(m => {
          const symbol = symbolByStockId[m.stockId];
          return (
            <li key={m.stockId}>
              <Link
                href={symbol ? `/stocks/${symbol}` : "#"}
                className="flex items-center justify-between p-2 rounded hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors"
              >
                <span className="text-sm font-medium dark:text-dracula-fg line-clamp-1 flex-1">{m.title}</span>
                <div className="flex items-center gap-2 ml-2">
                  <span className="text-xs text-gray-400 dark:text-dracula-comment">{m.eventCount}건</span>
                  <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${COLOR[m.latestType] ?? ""}`}>
                    {LABEL[m.latestType] ?? m.latestType}
                  </span>
                </div>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
