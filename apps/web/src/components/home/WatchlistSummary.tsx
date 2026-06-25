"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { useQuery } from "@tanstack/react-query";

interface WatchlistItem { id: number; stockId: number; symbol: string; name: string; }
interface WatchlistGroup { id: number; name: string; items: WatchlistItem[]; }
interface RecentEvent {
  id: number; stockId: number; eventType: string;
  title: string; importanceScore: number; eventTime: string;
}

const EVENT_DOT: Record<string, string> = {
  PRICE_SPIKE:          "bg-[#0ecb81]",
  PRICE_DROP:           "bg-[#f6465d]",
  VOLUME_SURGE:         "bg-[#f1fa8c]",
  DISCLOSURE_PUBLISHED: "bg-[#bd93f9]",
};

export default function WatchlistSummary() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: groups = [] } = useQuery<WatchlistGroup[]>({
    queryKey: ["watchlist", "home"],
    queryFn:  async () => {
      const r = await authFetch("/api/watchlists");
      return r.ok ? r.json() : [];
    },
    enabled:   isLoggedIn,
    staleTime: 30_000,
  });

  const { data: events = [] } = useQuery<RecentEvent[]>({
    queryKey: ["events", "recent", "home"],
    queryFn:  async () => {
      const r = await fetch("/api/events/recent?limit=50");
      return r.ok ? r.json() : [];
    },
    refetchInterval: 10_000,
    staleTime:       10_000,
  });

  if (!isLoggedIn) return null;
  if (!groups.length) return null;

  const watchlistStockIds = new Set(groups.flatMap((g: WatchlistGroup) => g.items.map((i: WatchlistItem) => i.stockId)));
  const watchlistMap = Object.fromEntries(
    groups.flatMap((g: WatchlistGroup) => g.items.map((i: WatchlistItem) => [i.stockId, i] as [number, WatchlistItem]))
  );

  // 관심종목 중 최근 이벤트 발생 종목 (중복 제거, 최신순 3개)
  const alertedStocks = (Array.from(
    events
      .filter((e: RecentEvent) => watchlistStockIds.has(e.stockId))
      .reduce((map: Map<number, RecentEvent>, e: RecentEvent) => {
        if (!map.has(e.stockId)) map.set(e.stockId, e);
        return map;
      }, new Map<number, RecentEvent>())
      .values()
  ) as RecentEvent[]).slice(0, 4);

  if (!alertedStocks.length) return (
    <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl p-4">
      <h2 className="text-sm font-semibold dark:text-[#f8f8f2] mb-2">관심종목 동향</h2>
      <p className="text-xs dark:text-[#6272a4] py-2">관심종목에서 최근 이벤트가 없습니다.</p>
    </div>
  );

  return (
    <div className="border dark:border-[#44475a] dark:bg-[#282a36] rounded-xl p-4">
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-sm font-semibold dark:text-[#f8f8f2]">관심종목 동향</h2>
        <Link href="/watchlist" className="text-[10px] dark:text-[#6272a4] hover:dark:text-[#bd93f9]">전체 보기 →</Link>
      </div>
      <ul className="space-y-2">
        {alertedStocks.map((event: RecentEvent) => {
          const stock = watchlistMap[event.stockId];
          if (!stock) return null;
          return (
            <li key={event.stockId}>
              <Link href={`/stocks/${stock.symbol}`}
                className="flex items-center gap-3 hover:dark:bg-[#44475a]/20 rounded-lg px-2 py-1.5 transition-colors">
                <div className={`w-2 h-2 rounded-full shrink-0 ${EVENT_DOT[event.eventType] ?? "bg-[#6272a4]"}`} />
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium dark:text-[#f8f8f2] truncate">{stock.name}</p>
                  <p className="text-[10px] dark:text-[#6272a4] truncate">{event.title}</p>
                </div>
                <span className="text-[10px] dark:text-[#44475a] shrink-0">
                  {new Date(event.eventTime).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
