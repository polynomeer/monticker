"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

interface RecentEvent {
  id: number;
  stockId: number;
  eventType: string;
  title: string;
  importanceScore: number;
  eventTime: string;
}

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
  const [events, setEvents] = useState<RecentEvent[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchEvents = async () => {
      try {
        const res = await fetch("/api/events/recent?limit=10");
        if (res.ok) setEvents(await res.json());
      } finally {
        setLoading(false);
      }
    };
    fetchEvents();
    const id = setInterval(fetchEvents, 10_000);
    return () => clearInterval(id);
  }, []);

  return (
    <div className="border border-gray-200 dark:border-dracula-line dark:bg-dracula-bg rounded-lg p-4">
      <h2 className="font-semibold dark:text-dracula-fg mb-3">최근 이벤트</h2>
      {loading && (
        <div className="space-y-2">
          {[1,2,3].map(i => <div key={i} className="h-12 bg-gray-100 dark:bg-dracula-line rounded animate-pulse" />)}
        </div>
      )}
      {!loading && events.length === 0 && (
        <p className="text-gray-400 dark:text-dracula-comment text-sm py-4 text-center">Worker 실행 후 이벤트가 표시됩니다.</p>
      )}
      <ul className="space-y-2">
        {events.map(e => {
          const colorClass = EVENT_COLOR[e.eventType] ?? EVENT_COLOR.default;
          const label = EVENT_LABEL[e.eventType] ?? e.eventType;
          const time = new Date(e.eventTime).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" });
          return (
            <li key={e.id}>
              <Link href={`/stocks/${e.stockId}`} className={`block p-3 rounded-lg border ${colorClass} hover:opacity-80 transition-opacity`}>
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
