"use client";

import { useEffect, useRef } from "react";
import ScreenerRow from "./ScreenerRow";
import type { ScreenerItem } from "@/hooks/useScreener";

interface Props {
  items: ScreenerItem[];
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  onLoadMore: () => void;
}

const HEADERS = ["#", "종목명", "현재가", "등락률", "거래대금", "매수/매도 비율", "산업"];

export default function ScreenerTable({ items, loading, loadingMore, hasMore, onLoadMore }: Props) {
  const sentinelRef = useRef<HTMLDivElement>(null);

  // 무한스크롤 — IntersectionObserver
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const obs = new IntersectionObserver(
      ([entry]) => { if (entry.isIntersecting && hasMore && !loadingMore) onLoadMore(); },
      { threshold: 0.1 }
    );
    obs.observe(el);
    return () => obs.disconnect();
  }, [hasMore, loadingMore, onLoadMore]);

  if (loading) return (
    <div className="space-y-2 p-4">
      {Array.from({ length: 10 }).map((_, i) => (
        <div key={i} className="h-14 rounded-lg bg-[#44475a]/30 animate-pulse" />
      ))}
    </div>
  );

  if (!items.length) return (
    <div className="text-center py-16 text-[#6272a4]">데이터가 없습니다.</div>
  );

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[720px]">
        <thead>
          <tr className="border-b border-[#44475a]">
            {HEADERS.map(h => (
              <th key={h} className="py-2 px-3 text-left text-xs text-[#6272a4] font-medium whitespace-nowrap first:pl-4">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map(item => <ScreenerRow key={item.stockId} item={item} />)}
        </tbody>
      </table>

      {/* 무한스크롤 센티넬 */}
      <div ref={sentinelRef} className="h-4" />
      {loadingMore && (
        <div className="text-center py-4">
          <span className="text-sm text-[#6272a4] animate-pulse">불러오는 중...</span>
        </div>
      )}
    </div>
  );
}
