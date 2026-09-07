import { authFetch } from "./api";

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
    throw new Error(err.error ?? "전략 공유에 실패했습니다.");
  }
  return res.json();
}
