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
  PRICE_SPIKE:          "bg-green-50 dark:bg-[#26a69a]/10 border-green-200 dark:border-[#26a69a]/25 text-green-800 dark:text-[#26a69a]",
  PRICE_DROP:           "bg-red-50 dark:bg-[#ef5350]/10 border-red-200 dark:border-[#ef5350]/25 text-red-800 dark:text-[#ef5350]",
  VOLUME_SURGE:         "bg-blue-50 dark:bg-[#f1fa8c]/10 border-blue-200 dark:border-[#f1fa8c]/25 text-blue-800 dark:text-[#f1fa8c]",
  DISCLOSURE_PUBLISHED: "bg-purple-50 dark:bg-[#bd93f9]/10 border-purple-200 dark:border-[#bd93f9]/25 text-purple-800 dark:text-[#bd93f9]",
  default:              "bg-gray-50 dark:bg-[#44475a] border-gray-200 dark:border-[#6272a4] text-gray-800 dark:text-[#f8f8f2]",
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
    <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg p-4">
      <h2 className="font-semibold dark:text-[#f8f8f2] mb-3">최근 이벤트</h2>
      {loading && (
        <div className="space-y-2">
          {[1,2,3].map(i => <div key={i} className="h-12 bg-gray-100 dark:bg-[#44475a] rounded animate-pulse" />)}
        </div>
      )}
      {!loading && events.length === 0 && (
        <p className="text-gray-400 dark:text-[#6272a4] text-sm py-4 text-center">Worker 실행 후 이벤트가 표시됩니다.</p>
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
