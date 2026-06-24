"use client";

import { useQuery } from "@tanstack/react-query";
import { stockKeys } from "@/hooks/useStockChart";

interface StockEvent {
  id: number; stockId: number; eventType: string;
  title: string; description: string | null;
  eventTime: string; importanceScore: number;
}

const EVENT_COLORS: Record<string, string> = {
  PRICE_SPIKE:          "bg-green-100 dark:bg-[#0ecb81]/40 dark:text-[#f8f8f2] dark:border-transparent",
  PRICE_DROP:           "bg-red-100 dark:bg-[#f6465d]/40 dark:text-[#f8f8f2] dark:border-transparent",
  VOLUME_SURGE:         "bg-blue-100 dark:bg-[#f1fa8c]/40 dark:text-[#f8f8f2] dark:border-transparent",
  NEWS_PUBLISHED:       "bg-yellow-100 dark:bg-[#8be9fd]/40 dark:text-[#f8f8f2] dark:border-transparent",
  DISCLOSURE_PUBLISHED: "bg-purple-100 dark:bg-[#bd93f9]/40 dark:text-[#f8f8f2] dark:border-transparent",
  SECTOR_MOVE:          "bg-gray-100 dark:bg-[#44475a] text-gray-800 dark:text-[#f8f8f2] border-gray-200 dark:border-[#6272a4]",
};

interface Props { stockId: number; }

export default function EventTimeline({ stockId }: Props) {
  // stockKeys.events(stockId) === useStockChart의 events 키와 동일
  // → TanStack Query가 두 컴포넌트의 요청을 하나로 병합 (Single Flight)
  const { data: events = [], isLoading } = useQuery<StockEvent[]>({
    queryKey:        stockKeys.events(stockId),
    queryFn:         async () => {
      const res = await fetch(`/api/stocks/${stockId}/events`);
      return res.ok ? res.json() : [];
    },
    refetchInterval: 10_000,
    staleTime:       10_000,
  });

  if (isLoading) return (
    <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg p-4">
      <div className="h-4 bg-gray-100 dark:bg-[#44475a] rounded w-32 mb-3 animate-pulse" />
      {[1,2,3].map(i => <div key={i} className="h-12 bg-gray-50 dark:bg-[#44475a] rounded mb-2 animate-pulse" />)}
    </div>
  );

  return (
    <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg p-4">
      <h3 className="font-semibold mb-3 dark:text-[#f8f8f2]">이벤트 타임라인</h3>
      {events.length === 0 ? (
        <p className="text-gray-400 dark:text-[#6272a4] text-sm text-center py-6">
          최근 24시간 내 이벤트가 없습니다.
        </p>
      ) : (
        <ul className="space-y-2">
          {events.map((event: StockEvent) => (
            <li key={event.id}
              className={`flex items-start gap-3 p-3 rounded-lg border text-sm ${
                EVENT_COLORS[event.eventType] ?? "bg-gray-50 dark:bg-[#44475a] text-gray-700 dark:text-[#f8f8f2] border-gray-200 dark:border-[#44475a]"
              }`}>
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
              <span className="text-xs font-bold opacity-70 shrink-0">{event.importanceScore}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
