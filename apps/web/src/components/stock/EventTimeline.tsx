"use client";

import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import { EventDot, Muted, SkeletonRows, eventMeta, fmtWhen } from "./parts";

interface StockEvent {
  id: number;
  stockId: number;
  eventType: string;
  title: string;
  description: string | null;
  eventTime: string;
  importanceScore: number;
}

const NEWS_LIKE_TYPES = new Set(["NEWS_PUBLISHED", "DISCLOSURE_PUBLISHED"]);

interface Props {
  stockId: number;
  /** 제목 없이 목록만 렌더링 (패널 탭 안에 임베드할 때) */
  bare?: boolean;
  /** 차트 마커 클릭 등으로 특정 이벤트로 점프할 때 — 해당 이벤트로 스크롤하고 잠깐 강조 */
  highlightEventId?: number | null;
  /** 뉴스성 이벤트 행에 "관련 뉴스 보기" 버튼을 노출하고, 누르면 뉴스 탭으로 전환 */
  onViewNews?: () => void;
}

/** 시안의 이벤트 패널 목록 — 시각 · 원형 마커 · 제목/설명 · 중요도. 5초마다 갱신. */
export default function EventTimeline({ stockId, bare = false, highlightEventId, onViewNews }: Props) {
  const [events, setEvents] = useState<StockEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [flashId, setFlashId] = useState<number | null>(null);
  const itemRefs = useRef<Record<number, HTMLLIElement | null>>({});

  const fetchEvents = async () => {
    try {
      const res = await fetch(`/api/stocks/${stockId}/events`);
      if (res.ok) setEvents(await res.json());
    } catch {
      /* 다음 폴링에서 다시 시도 */
    }
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
    if (!events.some((e) => e.id === highlightEventId)) return;
    setFlashId(highlightEventId);
    itemRefs.current[highlightEventId]?.scrollIntoView({ behavior: "smooth", block: "center" });
    const t = setTimeout(() => setFlashId(null), 1800);
    return () => clearTimeout(t);
  }, [highlightEventId, events, loading]);

  return (
    <div>
      {!bare && <h3 className="m-0 mb-2 text-15 font-bold text-dracula-fg">이벤트 타임라인</h3>}

      {loading && <SkeletonRows n={3} />}

      {!loading && events.length === 0 && <Muted>최근 24시간 내 이벤트가 없습니다.</Muted>}

      {!loading && events.length > 0 && (
        <ol className="m-0 list-none p-0">
          {events.map((event) => (
            <li
              key={event.id}
              ref={(el) => {
                itemRefs.current[event.id] = el;
              }}
              className={cn(
                "flex gap-2.5 border-b border-tm-line px-1 py-[9px] transition-colors duration-300",
                flashId === event.id && "rounded-md bg-[#3a2f52] ring-1 ring-dracula-purple",
              )}
            >
              <span className="num w-[34px] flex-none pt-0.5 text-2xs text-tm-muted">{fmtWhen(event.eventTime)}</span>
              <EventDot type={event.eventType} />
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="text-13 font-semibold">{event.title}</span>
                <span className="text-xs text-tm-muted">
                  {[eventMeta(event.eventType).label, event.description, `중요도 ${event.importanceScore}`].filter(Boolean).join(" · ")}
                </span>
                {onViewNews && NEWS_LIKE_TYPES.has(event.eventType) && (
                  <button type="button" onClick={onViewNews} className="self-start text-2xs text-dracula-purple hover:underline">
                    관련 뉴스 보기 →
                  </button>
                )}
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
