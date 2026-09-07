"use client";

import { useEffect, useRef, useState } from "react";
import type { ScreenerItem } from "@/hooks/useScreener";
import { browseScreener, searchScreener, getScreenerQuotes } from "@/services/screener";

/**
 * 룰셋의 유니버스 필터(market/marketCapTier)로 좁힌 종목 중에서 검색해 고르는 선택기.
 * 백테스트/포워드 테스트 패널에서 하드코딩된 8종목 드롭다운 대신 쓴다.
 */
export function StockPicker({
  market, marketCapTier, value, onChange,
}: {
  market: string;
  marketCapTier: string;
  value: number;
  onChange: (stockId: number) => void;
}) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [results, setResults] = useState<ScreenerItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<ScreenerItem | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // 현재 선택된 종목의 표시용 라벨 조회
  useEffect(() => {
    let cancelled = false;
    getScreenerQuotes([value]).then(items => {
      if (!cancelled) setSelected(items[0] ?? null);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [value]);

  // 유니버스/검색어 변경 시 후보 목록 조회 (디바운스)
  useEffect(() => {
    if (!open) return;
    setLoading(true);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      const fetcher = query.trim()
        ? searchScreener(query.trim(), market, marketCapTier)
        : browseScreener(market, marketCapTier);
      fetcher.then(setResults).catch(() => setResults([])).finally(() => setLoading(false));
    }, 300);
    return () => clearTimeout(debounceRef.current);
  }, [query, market, marketCapTier, open]);

  // 바깥 클릭 시 닫기
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  return (
    <div className="relative" ref={containerRef}>
      <input
        type="text"
        aria-label="종목 선택"
        placeholder="종목명·코드 검색"
        value={open ? query : selected ? `${selected.name} (${selected.symbol})` : ""}
        onFocus={() => { setOpen(true); setQuery(""); }}
        onChange={e => setQuery(e.target.value)}
        className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
      />
      {open && (
        <div className="absolute z-20 mt-1 w-full max-h-56 overflow-y-auto rounded-lg bg-white dark:bg-dracula-surface border border-gray-200 dark:border-dracula-line shadow-lg">
          {loading ? (
            <div className="px-3 py-2 text-xs text-gray-500 dark:text-dracula-comment">검색 중...</div>
          ) : results.length === 0 ? (
            <div className="px-3 py-2 text-xs text-gray-500 dark:text-dracula-comment">일치하는 종목이 없습니다.</div>
          ) : (
            results.map(item => (
              <button
                key={item.stockId}
                type="button"
                onClick={() => { onChange(item.stockId); setSelected(item); setOpen(false); }}
                className="w-full flex items-center justify-between gap-2 px-3 py-2 text-xs text-left hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors"
              >
                <span className="text-gray-900 dark:text-dracula-fg font-medium">{item.name}</span>
                <span className="text-gray-400 dark:text-dracula-comment tabular-nums">{item.symbol} · {item.market}</span>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
