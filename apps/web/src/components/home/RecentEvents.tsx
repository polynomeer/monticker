"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

interface RecentEvent {
  id: number;
  stockId: number;
  eventType: string;
  title: string;
  importanceScore: number;
  eventTime: string;
}

interface QuoteItem { stockId: number; symbol: string; }

const EVENT_COLOR: Record<string, string> = {
  PRICE_SPIKE:          "bg-green-50 dark:bg-market-up/25 border-green-200 dark:border-market-up/25 text-green-800 dark:text-market-up",
  PRICE_DROP:           "bg-red-50 dark:bg-market-down/25 border-red-200 dark:border-market-down/25 text-red-800 dark:text-market-down",
  VOLUME_SURGE:         "bg-blue-50 dark:bg-dracula-yellow/25 border-blue-200 dark:border-dracula-yellow/25 text-blue-800 dark:text-dracula-yellow",
  DISCLOSURE_PUBLISHED: "bg-purple-50 dark:bg-dracula-purple/25 border-purple-200 dark:border-dracula-purple/25 text-purple-800 dark:text-dracula-purple",
  default:              "bg-gray-50 dark:bg-dracula-line border-gray-200 dark:border-dracula-comment text-gray-800 dark:text-dracula-fg",
};

const EVENT_LABEL: Record<string, string> = {
  PRICE_SPIKE:          "가격 급등",
  PRICE_DROP:           "가격 급락",
  VOLUME_SURGE:         "거래량 급증",
  DISCLOSURE_PUBLISHED: "공시",
};

export default function RecentEvents() {
  // WatchlistSummary/TopMovers와 같은 queryKey+fetcher를 써서 홈 화면에 셋이 같이
  // 떠도 react-query가 요청을 하나로 합친다(限 개별 setInterval 3개로 중복 폴링하지 않음).
  const { data: events = [], isLoading: loading } = useQuery<RecentEvent[]>({
    queryKey: ["events", "recent", "home"],
    queryFn:  async () => {
      const r = await fetch("/api/events/recent?limit=50");
      return r.ok ? r.json() : [];
    },
    refetchInterval: 10_000,
    staleTime:       10_000,
  });

  const displayed = events.slice(0, 10);
  const stockIds = Array.from(new Set(displayed.map(e => e.stockId)));

  const { data: quotes = [] } = useQuery<QuoteItem[]>({
    queryKey: ["screener", "quotes", "home-events", stockIds],
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

  return (
    <div className="border border-gray-200 dark:border-dracula-line dark:bg-dracula-bg rounded-lg p-4">
      <h2 className="font-semibold dark:text-dracula-fg mb-3">최근 이벤트</h2>
      {loading && (
        <div className="space-y-2">
          {[1,2,3].map(i => <div key={i} className="h-12 bg-gray-100 dark:bg-dracula-line rounded animate-pulse" />)}
        </div>
      )}
      {!loading && displayed.length === 0 && (
        <p className="text-gray-400 dark:text-dracula-comment text-sm py-4 text-center">Worker 실행 후 이벤트가 표시됩니다.</p>
      )}
      <ul className="space-y-2">
        {displayed.map(e => {
          const colorClass = EVENT_COLOR[e.eventType] ?? EVENT_COLOR.default;
          const label = EVENT_LABEL[e.eventType] ?? e.eventType;
          const time = new Date(e.eventTime).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" });
          const symbol = symbolByStockId[e.stockId];
          return (
            <li key={e.id}>
              <Link href={symbol ? `/stocks/${symbol}` : "#"} className={`block p-3 rounded-lg border ${colorClass} hover:opacity-80 transition-opacity`}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-medium">{label}</span>
                    <span className="text-sm">{e.title}</span>
                  </div>
                  <div className="flex items-center gap-2 text-xs opacity-70">
                    <span>{time}</span>
                    <span className="font-bold">{e.importanceScore}</span>
                  </div>
                </div>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
