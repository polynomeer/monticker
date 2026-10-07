import type { MarketSignal, MarketSignalFeed } from "@monticker/types";
import { authFetch } from "./api";

/** 구독 중인 전략들의 최근 신호와 이번 달 신호 수 */
export async function getSubscribedSignals(limit = 30): Promise<MarketSignalFeed> {
  const res = await authFetch(`/api/quant/market/signals?limit=${limit}`);
  if (!res.ok) throw new Error("구독 전략 신호를 불러오지 못했습니다.");
  return res.json();
}

/** 전략 하나의 신호 이력 — 구독자·제작자만(아니면 403) */
export async function getStrategySignals(marketId: number, limit = 10): Promise<MarketSignal[]> {
  const res = await authFetch(`/api/quant/market/${marketId}/signals?limit=${limit}`);
  if (res.status === 403) throw new Error("이 전략을 구독해야 신호 이력을 볼 수 있습니다.");
  if (!res.ok) throw new Error("신호 이력을 불러오지 못했습니다.");
  return res.json();
}

export interface ShareStrategyResult {
  id: number;
  rulesetId: string;
  price: number;
}

export async function shareStrategy(rulesetId: string, description: string, price: number): Promise<ShareStrategyResult> {
  const res = await authFetch("/api/quant/market/share", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rulesetId, description: description || null, price }),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message ?? "전략 공유에 실패했습니다.");
  }
  return res.json();
}
