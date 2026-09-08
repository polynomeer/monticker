import type { ScreenerItem } from "@/hooks/useScreener";
import { authFetch } from "./api";

interface ScreenerListResponse {
  items: ScreenerItem[];
  total: number;
  hasMore: boolean;
}

export async function browseScreener(market: string, marketCapTier: string, limit = 30): Promise<ScreenerItem[]> {
  const res = await authFetch(`/api/screener?market=${market}&marketCapTier=${marketCapTier}&sort=amount&limit=${limit}`);
  if (!res.ok) throw new Error("종목 목록을 불러오지 못했습니다.");
  const data: ScreenerListResponse = await res.json();
  return data.items;
}

export async function searchScreener(query: string, market: string, marketCapTier: string, limit = 30): Promise<ScreenerItem[]> {
  const params = new URLSearchParams({ query, market, marketCapTier, limit: String(limit) });
  const res = await authFetch(`/api/screener/search?${params}`);
  if (!res.ok) throw new Error("종목 검색에 실패했습니다.");
  const data: ScreenerListResponse = await res.json();
  return data.items;
}

export async function getScreenerQuotes(stockIds: number[]): Promise<ScreenerItem[]> {
  if (stockIds.length === 0) return [];
  const res = await authFetch(`/api/screener/quotes?ids=${stockIds.join(",")}`);
  if (!res.ok) throw new Error("종목 시세를 불러오지 못했습니다.");
  const data: ScreenerListResponse = await res.json();
  return data.items;
}
