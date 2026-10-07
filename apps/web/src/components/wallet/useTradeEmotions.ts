"use client";

import { authFetch } from "@/services/api";
import type { EmotionTag } from "./emotions";

export const emotionKey = (tradeId: number) => ["wallet", "emotion", tradeId] as const;

/**
 * 거래 한 건의 감정 태그(GET /api/paper/trades/{id}/emotion) — 영수증 카드가 저장된 값을 미리 선택할 때 쓴다.
 * 목록 화면은 거래마다 부르지 않는다: 거래 내역·리플레이 응답에 감정 태그가 함께 온다(ADR-085).
 */
export async function fetchEmotion(tradeId: number): Promise<EmotionTag | null> {
  const r = await authFetch(`/api/paper/trades/${tradeId}/emotion`);
  // 태그가 없으면 404 — 정상 상태다
  if (!r.ok) return null;
  return r.json();
}
