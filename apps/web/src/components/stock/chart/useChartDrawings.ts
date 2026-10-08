"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { getAccessToken } from "@/services/auth";
import type { ChartType, Drawing } from "./types";
import {
  CHART_PREFS_KEY, drawingsStorageKey, loadDrawings, migrateLegacyDrawings, saveDrawings, userScopeFromToken,
} from "./drawingStorage";

function safeLocalStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

/**
 * 종목 차트 드로잉 — 사용자·종목별로 이 브라우저에 저장한다(봉 간격과 무관하게 하나).
 * 처음 열 때 예전 형식(종목 id·간격별 키)이 있으면 한 번 옮긴다.
 */
export function useChartDrawings(symbol: string, stockId: number, isLoggedIn: boolean, legacyIntervals: readonly string[]) {
  const scope = useMemo(() => (isLoggedIn ? userScopeFromToken(getAccessToken()) : "anon"), [isLoggedIn]);
  const key = drawingsStorageKey(scope, symbol);
  const [drawings, setDrawings] = useState<Drawing[]>([]);
  // 간격 목록은 호출부 상수 — 바뀌어도 다시 읽을 필요는 없다
  const legacyKey = legacyIntervals.join(",");

  useEffect(() => {
    const storage = safeLocalStorage();
    const migrated = migrateLegacyDrawings(storage, stockId, legacyKey.split(","), key);
    setDrawings(migrated ?? loadDrawings(storage, key));
  }, [key, stockId, legacyKey]);

  const save = useCallback((next: Drawing[]) => {
    setDrawings(saveDrawings(safeLocalStorage(), key, next));
  }, [key]);

  return [drawings, save] as const;
}

export interface ChartPrefs {
  chartType: ChartType;
  magnet: boolean;
}

const CHART_TYPES: ReadonlySet<ChartType> = new Set(["candle", "line", "area", "heikin-ashi"]);
const DEFAULT_PREFS: ChartPrefs = { chartType: "candle", magnet: false };

export function parseChartPrefs(raw: string | null): ChartPrefs {
  try {
    const p = raw ? (JSON.parse(raw) as Partial<ChartPrefs>) : null;
    return {
      chartType: p && CHART_TYPES.has(p.chartType as ChartType) ? (p.chartType as ChartType) : DEFAULT_PREFS.chartType,
      magnet: p?.magnet === true,
    };
  } catch {
    return DEFAULT_PREFS;
  }
}

/** 차트 유형·자석 — 이 브라우저에 기억한다 */
export function useChartPrefs() {
  const [prefs, setPrefs] = useState<ChartPrefs>(DEFAULT_PREFS);
  useEffect(() => {
    setPrefs(parseChartPrefs(safeLocalStorage()?.getItem(CHART_PREFS_KEY) ?? null));
  }, []);
  const update = useCallback((patch: Partial<ChartPrefs>) => {
    setPrefs((cur) => {
      const next = { ...cur, ...patch };
      try { safeLocalStorage()?.setItem(CHART_PREFS_KEY, JSON.stringify(next)); } catch { /* 기억 못 해도 화면은 동작 */ }
      return next;
    });
  }, []);
  return [prefs, update] as const;
}
