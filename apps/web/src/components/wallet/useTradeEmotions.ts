"use client";

import { useMemo } from "react";
import { useQueries } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import type { EmotionTag } from "./emotions";

export const emotionKey = (tradeId: number) => ["wallet", "emotion", tradeId] as const;

export async function fetchEmotion(tradeId: number): Promise<EmotionTag | null> {
  const r = await authFetch(`/api/paper/trades/${tradeId}/emotion`);
  // 태그가 없으면 404 — 정상 상태다
  if (!r.ok) return null;
  return r.json();
}

/** 거래별 감정 태그(GET /api/paper/trades/{id}/emotion). 목록 API에 태그가 없어 거래마다 조회한다. */
export function useTradeEmotions(tradeIds: number[], enabled = true) {
  const results = useQueries({
    queries: tradeIds.map((id) => ({
      queryKey: emotionKey(id),
      queryFn: () => fetchEmotion(id),
      staleTime: 5 * 60_000,
      enabled,
    })),
  });
  const sig = results.map((r) => r.data?.emotion ?? "").join(",");
  return useMemo(() => {
    const m = new Map<number, string | null>();
    results.forEach((r, i) => m.set(tradeIds[i], r.data?.emotion ?? null));
    return m;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig, tradeIds.join(",")]);
}
