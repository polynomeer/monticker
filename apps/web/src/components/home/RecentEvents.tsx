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
  PRICE_SPIKE:          "bg-green-50 border-green-200 text-green-800",
  PRICE_DROP:           "bg-red-50 border-red-200 text-red-800",
  VOLUME_SURGE:         "bg-blue-50 border-blue-200 text-blue-800",
  DISCLOSURE_PUBLISHED: "bg-purple-50 border-purple-200 text-purple-800",
  default:              "bg-gray-50 border-gray-200 text-gray-800",
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
    <div className="border border-gray-200 rounded-lg p-4">
      <h2 className="font-semibold mb-3">최근 이벤트</h2>
      {loading && (
        <div className="space-y-2">
          {[1,2,3].map(i => <div key={i} className="h-12 bg-gray-100 rounded animate-pulse" />)}
        </div>
      )}
      {!loading && events.length === 0 && (
        <p className="text-gray-400 text-sm py-4 text-center">Worker 실행 후 이벤트가 표시됩니다.</p>
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
