"use client";

import { useEffect, useState } from "react";

interface StockEvent {
  id: number;
  stockId: number;
  eventType: string;
  title: string;
  description: string | null;
  eventTime: string;
  importanceScore: number;
}

const EVENT_COLORS: Record<string, string> = {
  PRICE_SPIKE:          "bg-green-100 text-green-800 border-green-200",
  PRICE_DROP:           "bg-red-100 text-red-800 border-red-200",
  VOLUME_SURGE:         "bg-blue-100 text-blue-800 border-blue-200",
  NEWS_PUBLISHED:       "bg-yellow-100 text-yellow-800 border-yellow-200",
  DISCLOSURE_PUBLISHED: "bg-purple-100 text-purple-800 border-purple-200",
  SECTOR_MOVE:          "bg-gray-100 text-gray-800 border-gray-200",
};

interface Props {
  stockId: number;
}

export default function EventTimeline({ stockId }: Props) {
  const [events, setEvents] = useState<StockEvent[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchEvents = async () => {
    const res = await fetch(`/api/stocks/${stockId}/events`);
    if (res.ok) setEvents(await res.json());
    setLoading(false);
  };

  useEffect(() => {
    fetchEvents();
    const interval = setInterval(fetchEvents, 5000);
    return () => clearInterval(interval);
  }, [stockId]);

  if (loading) {
    return (
      <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg p-4">
        <div className="h-4 bg-gray-100 dark:bg-[#44475a] rounded w-32 mb-3 animate-pulse" />
        {[1, 2, 3].map((i) => (
          <div key={i} className="h-12 bg-gray-50 dark:bg-[#44475a] rounded mb-2 animate-pulse" />
        ))}
      </div>
    );
  }

  return (
    <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg p-4">
      <h3 className="font-semibold mb-3 dark:text-[#f8f8f2]">이벤트 타임라인</h3>

      {events.length === 0 ? (
        <p className="text-gray-400 dark:text-[#6272a4] text-sm text-center py-6">
          최근 24시간 내 이벤트가 없습니다.
        </p>
      ) : (
        <ul className="space-y-2">
          {events.map((event) => (
            <li
              key={event.id}
              className={`flex items-start gap-3 p-3 rounded-lg border text-sm dark:bg-[#44475a] dark:border-[#44475a] dark:text-[#f8f8f2] ${
                EVENT_COLORS[event.eventType] ?? "bg-gray-50 text-gray-700 border-gray-200"
              }`}
            >
              <div className="flex-1">
                <div className="flex items-center justify-between">
                  <span className="font-medium">{event.title}</span>
                  <span className="text-xs opacity-60">
                    {new Date(event.eventTime).toLocaleTimeString("ko-KR")}
                  </span>
                </div>
                {event.description && (
                  <p className="text-xs mt-1 opacity-75">{event.description}</p>
                )}
              </div>
              <span className="text-xs font-semibold shrink-0 opacity-60">
                {event.importanceScore}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
