"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/Badge";
import { EmptyState } from "@/components/ui/EmptyState";
import { Skeleton } from "@/components/ui/Skeleton";
import { Card } from "@/components/ui/Card";

interface StockEvent {
  id: number;
  stockId: number;
  eventType: string;
  title: string;
  description: string | null;
  eventTime: string;
  importanceScore: number;
}

type BadgeVariant = "up" | "down" | "neutral" | "info" | "purple";

const EVENT_BADGE: Record<string, BadgeVariant> = {
  PRICE_SPIKE:          "up",
  PRICE_DROP:           "down",
  VOLUME_SURGE:         "info",
  NEWS_PUBLISHED:       "neutral",
  DISCLOSURE_PUBLISHED: "purple",
  SECTOR_MOVE:          "neutral",
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
    // fetchEvents is stable per stockId — eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stockId]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Card className="p-4">
      <h3 className="font-semibold text-gray-900 dark:text-[#f8f8f2] mb-3">이벤트 타임라인</h3>

      {loading && (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-14 w-full rounded-lg" />
          ))}
        </div>
      )}

      {!loading && events.length === 0 && (
        <EmptyState
          icon="📅"
          title="이벤트 없음"
          description="최근 24시간 내 이벤트가 없습니다."
        />
      )}

      {!loading && events.length > 0 && (
        <ul className="space-y-2">
          {events.map((event) => (
            <li
              key={event.id}
              className="flex items-start gap-3 p-3 rounded-lg border border-gray-200 dark:border-[#44475a]/60 bg-gray-50 dark:bg-[#282a36] text-sm hover:border-gray-300 dark:hover:border-[#6272a4]/60 transition-colors"
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-gray-900 dark:text-[#f8f8f2] truncate">
                    {event.title}
                  </span>
                  <span className="text-xs text-gray-500 dark:text-[#6272a4] shrink-0">
                    {new Date(event.eventTime).toLocaleTimeString("ko-KR")}
                  </span>
                </div>
                {event.description && (
                  <p className="text-xs mt-1 text-gray-500 dark:text-[#6272a4]">{event.description}</p>
                )}
                <div className="mt-1.5">
                  <Badge variant={EVENT_BADGE[event.eventType] ?? "neutral"}>
                    {event.eventType.replace(/_/g, " ")}
                  </Badge>
                </div>
              </div>
              <span className="text-xs font-semibold text-gray-500 dark:text-[#6272a4] shrink-0 tabular-nums">
                {event.importanceScore}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
