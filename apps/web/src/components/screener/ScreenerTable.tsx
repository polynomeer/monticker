"use client";

import { useRef, useCallback } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { cn } from "@/lib/utils";
import { Icon } from "@/components/terminal";
import ScreenerRow from "./ScreenerRow";
import type { ScreenerItem } from "@/hooks/useScreener";

/**
 * 표시 컬럼 묶음.
 * - basic: 시안(Screener.dc.html)의 결과 표 그대로 — 거래량 배수·오늘 스파크라인·이벤트는 아직 API가 없어 "—"
 * - trade: 기존 스크리너의 거래대금·매수/매도 비율
 * - valuation: 시가총액·PER·PBR
 */
export type ColumnSet = "basic" | "trade" | "valuation";

export type ColumnKey =
  | "star" | "name" | "price" | "change"
  | "volMult" | "today" | "event" | "marketCap" | "sector"
  | "amount" | "buySell" | "per" | "pbr" | "actions";

export interface ScreenerColumn {
  key: ColumnKey;
  label: string;
  /** 헤더·행이 공유하는 폭/정렬 클래스 */
  width: string;
}

const COL: Record<ColumnKey, ScreenerColumn> = {
  star:      { key: "star",      label: "",            width: "w-9 justify-center" },
  name:      { key: "name",      label: "종목",         width: "min-w-[180px] flex-1" },
  price:     { key: "price",     label: "현재가",       width: "w-28 justify-end" },
  change:    { key: "change",    label: "등락률",       width: "w-24 justify-end" },
  volMult:   { key: "volMult",   label: "거래량 배수",  width: "w-24 justify-end" },
  today:     { key: "today",     label: "오늘",         width: "w-[110px]" },
  event:     { key: "event",     label: "이벤트",       width: "w-20" },
  marketCap: { key: "marketCap", label: "시가총액",     width: "w-24 justify-end" },
  sector:    { key: "sector",    label: "섹터",         width: "w-24" },
  amount:    { key: "amount",    label: "거래대금",     width: "w-24 justify-end" },
  buySell:   { key: "buySell",   label: "매수/매도 비율", width: "w-36" },
  per:       { key: "per",       label: "PER",          width: "w-16 justify-end" },
  pbr:       { key: "pbr",       label: "PBR",          width: "w-16 justify-end" },
  actions:   { key: "actions",   label: "",             width: "w-[84px] justify-end" },
};

const SET_KEYS: Record<ColumnSet, ColumnKey[]> = {
  basic:     ["volMult", "today", "event", "marketCap", "sector"],
  trade:     ["amount", "buySell", "sector"],
  valuation: ["marketCap", "per", "pbr"],
};

export function columnsFor(set: ColumnSet): ScreenerColumn[] {
  return [COL.star, COL.name, COL.price, COL.change, ...SET_KEYS[set].map((k) => COL[k]), COL.actions];
}

interface Props {
  items: ScreenerItem[];
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  onLoadMore: () => void;
  columnSet?: ColumnSet;
}

const ROW_HEIGHT = 46;   // px — ScreenerRow 고정 높이
const OVERSCAN   = 5;    // 뷰포트 위아래 여분 렌더 행 수
const MIN_WIDTH  = "min-w-[980px]";

export default function ScreenerTable({
  items, loading, loadingMore, hasMore, onLoadMore, columnSet = "basic",
}: Props) {
  const columns = columnsFor(columnSet);
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
    <div className="flex flex-col gap-1 p-2" aria-busy="true" aria-label="스크리너 불러오는 중">
      {Array.from({ length: 12 }).map((_, i) => (
        <div key={i}
          className="h-[40px] animate-shimmer rounded-lg bg-gradient-to-r from-tm-inner via-tm-raised to-tm-inner bg-[length:200%_100%]"
          style={{ animationDelay: `${i * 40}ms` }}
        />
      ))}
    </div>
  );

  if (!items.length) return (
    <div className="flex flex-col items-center gap-2 py-20 text-center text-tm-muted">
      <Icon name="search" size={32} className="opacity-50" />
      <span className="text-sm">데이터가 없습니다.</span>
    </div>
  );

  return (
    <div className="overflow-x-auto" role="table" aria-label="스크리너 결과" aria-rowcount={items.length + 1}>
      {/* 헤더 — 스크롤과 무관하게 고정 */}
      <div role="row" className={cn("flex items-center border-b border-tm-line", MIN_WIDTH)}>
        {columns.map((c) => (
          <div key={c.key} role="columnheader" className={cn("flex shrink-0 whitespace-nowrap px-3 py-2 text-2xs font-medium text-tm-muted", c.width)}>
            {c.label}
          </div>
        ))}
      </div>

      {/* 가상 스크롤 컨테이너 — 고정 높이로 스크롤 생성 */}
      <div ref={scrollRef} role="rowgroup" className={cn("overflow-y-auto", MIN_WIDTH)} style={{ height: "min(640px, 75vh)" }}>
        {/* 전체 높이 공간 확보 (가상화 핵심) */}
        <div style={{ height: totalHeight, position: "relative" }}>
          {virtualItems.map(virtualRow => {
            const item = items[virtualRow.index];
            return (
              <div
                key={virtualRow.key}
                data-index={virtualRow.index}
                ref={virtualizer.measureElement}
                style={{ position: "absolute", top: virtualRow.start, left: 0, width: "100%", height: ROW_HEIGHT }}
              >
                <ScreenerRow item={item} columns={columns} />
              </div>
            );
          })}
        </div>

        {/* 무한스크롤 트리거 — 가상 목록 맨 아래 */}
        <div ref={loadMoreRef} className="h-4" />
        {loadingMore && (
          <div className="py-3 text-center">
            <span className="animate-pulse text-xs text-tm-muted">불러오는 중...</span>
          </div>
        )}
      </div>

      {/* 행 수 표시 */}
      <div className="num border-t border-tm-line px-3 py-2 text-2xs text-tm-muted">
        {items.length.toLocaleString()}개 표시 중
        {hasMore && " (스크롤하면 더 보기)"}
        &nbsp;·&nbsp;DOM 렌더: {virtualItems.length}행
      </div>
    </div>
  );
}
