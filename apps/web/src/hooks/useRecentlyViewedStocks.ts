"use client";

import { useCallback, useEffect, useState } from "react";

export interface RecentlyViewedStock {
  stockId: number;
  symbol: string;
  name: string;
  market: string;
  viewedAt: number;
}

const STORAGE_KEY = "monticker:recentlyViewedStocks";
const MAX_ENTRIES = 10;

function read(): RecentlyViewedStock[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as RecentlyViewedStock[]) : [];
  } catch {
    return [];
  }
}

function write(entries: RecentlyViewedStock[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // 시크릿 모드 등 localStorage 차단 환경 — 조용히 무시
  }
}

/**
 * 관심종목(서버 저장, 명시적 즐겨찾기)과는 별개로 "최근에 열어본 종목"을
 * 브라우저 로컬에만 기록한다. 로그인 여부와 무관하게 동작한다.
 */
export function useRecentlyViewedStocks() {
  const [entries, setEntries] = useState<RecentlyViewedStock[]>([]);

  useEffect(() => { setEntries(read()); }, []);

  const record = useCallback((entry: Omit<RecentlyViewedStock, "viewedAt">) => {
    const next = [
      { ...entry, viewedAt: Date.now() },
      ...read().filter(e => e.stockId !== entry.stockId),
    ].slice(0, MAX_ENTRIES);
    write(next);
    setEntries(next);
  }, []);

  const clear = useCallback(() => {
    write([]);
    setEntries([]);
  }, []);

  return { entries, record, clear };
}
