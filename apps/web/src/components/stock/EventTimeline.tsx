"use client";

import { useEffect, useRef, useState } from "react";
import { CalendarBlank } from "@phosphor-icons/react";
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

const NEWS_LIKE_TYPES = new Set(["NEWS_PUBLISHED", "DISCLOSURE_PUBLISHED"]);

interface Props {
  stockId: number;
  /** 카드 테두리/제목 없이 내용만 렌더링 (탭 전환형 컨테이너에 임베드할 때) */
  bare?: boolean;
  /** 차트 마커 클릭 등으로 특정 이벤트로 점프할 때 — 해당 이벤트로 스크롤하고 잠깐 강조 */
  highlightEventId?: number | null;
  /** 뉴스성 이벤트 행에 "관련 뉴스 보기" 버튼을 노출하고, 누르면 뉴스 탭으로 전환 */
  onViewNews?: () => void;
}

export default function EventTimeline({ stockId, bare = false, highlightEventId, onViewNews }: Props) {
  const [events, setEvents] = useState<StockEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [flashId, setFlashId] = useState<number | null>(null);
  const itemRefs = useRef<Record<number, HTMLLIElement | null>>({});

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

  // 차트 마커 클릭으로 점프해온 경우 — 목록이 로드된 뒤에 해당 항목으로 스크롤+강조
  useEffect(() => {
    if (highlightEventId == null || loading) return;
    if (!events.some(e => e.id === highlightEventId)) return;
    setFlashId(highlightEventId);
    itemRefs.current[highlightEventId]?.scrollIntoView({ behavior: "smooth", block: "center" });
    const t = setTimeout(() => setFlashId(null), 1800);
    return () => clearTimeout(t);
  }, [highlightEventId, events, loading]);

  const content = (
    <>
      {!bare && <h3 className="font-semibold text-gray-900 dark:text-dracula-fg mb-3">이벤트 타임라인</h3>}

      {loading && (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-14 w-full rounded-lg" />
          ))}
        </div>
      )}

      {!loading && events.length === 0 && (
        <EmptyState
          icon={CalendarBlank}
          title="이벤트 없음"
          description="최근 24시간 내 이벤트가 없습니다."
        />
      )}

      {!loading && events.length > 0 && (
        <ul className="space-y-2">
          {events.map((event) => (
            <li
              key={event.id}
              ref={el => { itemRefs.current[event.id] = el; }}
              className={`flex items-start gap-3 p-3 rounded-lg border text-sm transition-all duration-300 ${
                flashId === event.id
                  ? "border-dracula-purple ring-2 ring-dracula-purple bg-dracula-purple/5"
                  : "border-gray-200 dark:border-dracula-line/60 bg-gray-50 dark:bg-dracula-bg hover:border-gray-300 dark:hover:border-dracula-comment/60"
              }`}
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-gray-900 dark:text-dracula-fg truncate">
                    {event.title}
                  </span>
                  <span className="text-xs text-gray-500 dark:text-dracula-comment shrink-0">
                    {new Date(event.eventTime).toLocaleTimeString("ko-KR")}
                  </span>
                </div>
                {event.description && (
                  <p className="text-xs mt-1 text-gray-500 dark:text-dracula-comment">{event.description}</p>
                )}
                <div className="mt-1.5 flex items-center gap-2 flex-wrap">
                  <Badge variant={EVENT_BADGE[event.eventType] ?? "neutral"}>
                    {event.eventType.replace(/_/g, " ")}
                  </Badge>
                  {onViewNews && NEWS_LIKE_TYPES.has(event.eventType) && (
                    <button
                      onClick={onViewNews}
                      className="text-[11px] text-blue-600 dark:text-dracula-purple hover:underline"
                    >
                      관련 뉴스 보기 →
                    </button>
                  )}
                </div>
              </div>
              <span className="text-xs font-semibold text-gray-500 dark:text-dracula-comment shrink-0 tabular-nums">
                {event.importanceScore}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  );

  if (bare) return <div>{content}</div>;
  return <Card className="p-4">{content}</Card>;
}
