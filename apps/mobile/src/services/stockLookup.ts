import { useEffect, useState } from "react";
import type { StockSearchItem } from "@monticker/types";
import { getJson } from "./useApi";

/**
 * 심볼 → 종목(id·이름). 증권사 잔고는 심볼만 주므로 이름과 이벤트 조회용 id를 여기서 붙인다.
 * 한 번 찾은 심볼은 앱이 떠 있는 동안 다시 묻지 않는다.
 */
const cache = new Map<string, StockSearchItem | null>();

export function useStocksBySymbols(symbols: string[]): Map<string, StockSearchItem> {
  const key = [...new Set(symbols)].sort().join(",");
  const [, setTick] = useState(0);

  useEffect(() => {
    const missing = key ? key.split(",").filter((s) => !cache.has(s)) : [];
    if (missing.length === 0) return;
    let alive = true;
    Promise.all(missing.map(async (sym) => {
      const r = await getJson<StockSearchItem[]>(`/api/stocks/search?query=${encodeURIComponent(sym)}`).catch(() => ({ data: null }));
      cache.set(sym, r.data?.find((s) => s.symbol === sym) ?? null);
    })).then(() => { if (alive) setTick((t) => t + 1); });
    return () => { alive = false; };
  }, [key]);

  const out = new Map<string, StockSearchItem>();
  for (const sym of key ? key.split(",") : []) {
    const hit = cache.get(sym);
    if (hit) out.set(sym, hit);
  }
  return out;
}
