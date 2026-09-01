"use client";

import { useEffect, useRef, useCallback } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import ScreenerRow from "./ScreenerRow";
import type { ScreenerItem } from "@/hooks/useScreener";

export type ColumnSet = "basic" | "valuation";

interface Props {
  items: ScreenerItem[];
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  onLoadMore: () => void;
  columnSet?: ColumnSet;
}

const ROW_HEIGHT = 52;   // px — ScreenerRow 고정 높이
const OVERSCAN   = 5;    // 뷰포트 위아래 여분 렌더 행 수

// 공통 컬럼(항상 표시) + 컬럼셋별로 바뀌는 중간 3칸 — ScreenerRow의 렌더링과 순서·개수를 맞춰야 함
const HEAD_COMMON       = [{ label: "#", width: "w-10" }, { label: "종목명", width: "flex-1 min-w-[160px]" }, { label: "현재가", width: "w-28 text-right" }, { label: "등락률", width: "w-28 text-right" }];
const HEAD_BASIC_EXTRA  = [{ label: "거래대금", width: "w-24 text-right" }, { label: "매수/매도 비율", width: "w-32" }, { label: "산업", width: "w-24" }];
const HEAD_VALUATION_EXTRA = [{ label: "시가총액", width: "w-24 text-right" }, { label: "PER", width: "w-16 text-right" }, { label: "PBR", width: "w-16 text-right" }];
const HEAD_TRAILING     = [{ label: "", width: "w-16 shrink-0" }];

export default function ScreenerTable({
  items, loading, loadingMore, hasMore, onLoadMore, columnSet = "basic",
}: Props) {
  const HEADERS = [
    ...HEAD_COMMON,
    ...(columnSet === "valuation" ? HEAD_VALUATION_EXTRA : HEAD_BASIC_EXTRA),
    ...HEAD_TRAILING,
  ];
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
        <div key={i}
          className="h-[52px] rounded-lg bg-gradient-to-r from-dracula-line/10 via-dracula-line/25 to-dracula-line/10 bg-[length:200%_100%] animate-shimmer"
          style={{ animationDelay: `${i * 40}ms` }}
        />
      ))}
    </div>
  );

  if (!items.length) return (
    <div className="flex flex-col items-center gap-2 text-center py-20 text-dracula-comment">
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="opacity-50">
        <circle cx="11" cy="11" r="7" />
        <path d="M21 21l-4.3-4.3" />
      </svg>
      <span className="text-sm">데이터가 없습니다.</span>
    </div>
  );

  return (
    <div className="overflow-x-auto">
      {/* 헤더 — 스크롤과 무관하게 고정 */}
      <div className="flex items-center border-b border-gray-200 dark:border-dracula-line px-2 py-2.5 min-w-[780px] bg-gray-50 dark:bg-dracula-line/5">
        {HEADERS.map(h => (
          <div key={h.label}
            className={`text-xs text-gray-500 dark:text-dracula-comment font-semibold tracking-wide px-2 ${h.width}`}>
            {h.label}
          </div>
        ))}
      </div>

      {/* 가상 스크롤 컨테이너 — 고정 높이로 스크롤 생성 */}
      <div
        ref={scrollRef}
        className="overflow-y-auto min-w-[780px]"
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
                <ScreenerRow item={item} columnSet={columnSet} />
              </div>
            );
          })}
        </div>

        {/* 무한스크롤 트리거 — 가상 목록 맨 아래 */}
        <div ref={loadMoreRef} className="h-4" />
        {loadingMore && (
          <div className="text-center py-3">
            <span className="text-xs text-dracula-comment animate-pulse">불러오는 중...</span>
          </div>
        )}
      </div>

      {/* 행 수 표시 */}
      <div className="px-4 py-2 text-[10px] text-gray-400 dark:text-dracula-line border-t border-gray-100 dark:border-dracula-line/30">
        {items.length.toLocaleString()}개 표시 중
        {hasMore && " (스크롤하면 더 보기)"}
        &nbsp;·&nbsp;DOM 렌더: {virtualItems.length}행
      </div>
    </div>
  );
}
