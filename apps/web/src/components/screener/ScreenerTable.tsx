"use client";

import { useEffect, useRef, useCallback } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import ScreenerRow from "./ScreenerRow";
import type { ScreenerItem } from "@/hooks/useScreener";

interface Props {
  items: ScreenerItem[];
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  onLoadMore: () => void;
}

const ROW_HEIGHT = 52;   // px — ScreenerRow 고정 높이
const OVERSCAN   = 5;    // 뷰포트 위아래 여분 렌더 행 수

const HEADERS = [
  { label: "#",            width: "w-10"  },
  { label: "종목명",        width: "flex-1 min-w-[160px]" },
  { label: "현재가",        width: "w-28 text-right" },
  { label: "등락률",        width: "w-28 text-right" },
  { label: "거래대금",      width: "w-24 text-right" },
  { label: "매수/매도 비율", width: "w-32" },
  { label: "산업",          width: "w-24" },
];

export default function ScreenerTable({
  items, loading, loadingMore, hasMore, onLoadMore,
}: Props) {
  const scrollRef = useRef<HTMLDivElement>(null);

  // ── 가상화 ────────────────────────────────────────────────
  const virtualizer = useVirtualizer({
    count:           items.length,
    getScrollElement: () => scrollRef.current,
    estimateSize:    () => ROW_HEIGHT,
    overscan:        OVERSCAN,
  });

  const virtualItems  = virtualizer.getVirtualItems();
  const totalHeight   = virtualizer.getTotalSize();

  // ── 무한스크롤: 마지막 가상 아이템이 보이면 추가 로드 ─────
  const loadMoreRef = useCallback((node: HTMLDivElement | null) => {
    if (!node) return;
    const obs = new IntersectionObserver(
      ([entry]) => { if (entry.isIntersecting && hasMore && !loadingMore) onLoadMore(); },
      { threshold: 0.1 }
    );
    obs.observe(node);
    return () => obs.disconnect();
  }, [hasMore, loadingMore, onLoadMore]);

  // ── 스켈레톤 ──────────────────────────────────────────────
  if (loading) return (
    <div className="space-y-1 p-2">
      {Array.from({ length: 12 }).map((_, i) => (
        <div key={i} className="h-[52px] rounded bg-[#44475a]/20 animate-pulse" />
      ))}
    </div>
  );

  if (!items.length) return (
    <div className="text-center py-16 text-[#6272a4] text-sm">데이터가 없습니다.</div>
  );

  return (
    <div className="overflow-x-auto">
      {/* 헤더 — 스크롤과 무관하게 고정 */}
      <div className="flex items-center border-b border-[#44475a] px-2 py-2 min-w-[720px]">
        {HEADERS.map(h => (
          <div key={h.label}
            className={`text-xs text-[#6272a4] font-medium px-2 ${h.width}`}>
            {h.label}
          </div>
        ))}
      </div>

      {/* 가상 스크롤 컨테이너 — 고정 높이로 스크롤 생성 */}
      <div
        ref={scrollRef}
        className="overflow-y-auto min-w-[720px]"
        style={{ height: "min(600px, 80vh)" }}
      >
        {/* 전체 높이 공간 확보 (가상화 핵심) */}
        <div style={{ height: totalHeight, position: "relative" }}>
          {virtualItems.map(virtualRow => {
            const item = items[virtualRow.index];
            return (
              <div
                key={virtualRow.key}
                data-index={virtualRow.index}
                ref={virtualizer.measureElement}
                style={{
                  position: "absolute",
                  top:    virtualRow.start,
                  left:   0,
                  width:  "100%",
                  height: ROW_HEIGHT,
                }}
              >
                <ScreenerRow item={item} />
              </div>
            );
          })}
        </div>

        {/* 무한스크롤 트리거 — 가상 목록 맨 아래 */}
        <div ref={loadMoreRef} className="h-4" />
        {loadingMore && (
          <div className="text-center py-3">
            <span className="text-xs text-[#6272a4] animate-pulse">불러오는 중...</span>
          </div>
        )}
      </div>

      {/* 행 수 표시 */}
      <div className="px-4 py-2 text-[10px] text-[#44475a] border-t border-[#44475a]/30">
        {items.length.toLocaleString()}개 표시 중
        {hasMore && " (스크롤하면 더 보기)"}
        &nbsp;·&nbsp;DOM 렌더: {virtualItems.length}행
      </div>
    </div>
  );
}
