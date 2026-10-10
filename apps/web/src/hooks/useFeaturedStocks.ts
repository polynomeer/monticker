"use client";

import { useQuery } from "@tanstack/react-query";

/**
 * 화면의 기본·빠른 선택 종목. **종목 코드로** 정해 두고 id는 서버에서 찾는다.
 *
 * 예전엔 id를 박아 두었는데(`{ id: 2, label: "삼성전자" }`, `{ id: 5, label: "AAPL" }` 등) 그 값은 개발 DB의 id였다.
 * 종목 id는 DB마다 다르다 — 빈 DB에 V12 시드를 넣으면 삼성전자가 1, AAPL은 150번대라 다른 환경에서는 칩·기본 종목이
 * 엉뚱한 종목을 가리킨다.
 */
export interface FeaturedSymbol {
  symbol: string;
  label: string;
  /** 같은 코드가 여러 시장에 있을 때 고를 시장(로컬 DB엔 035420이 KOSPI·KOSDAQ 두 행이다) */
  market?: string;
}
export interface FeaturedStock extends FeaturedSymbol { id: number }

export const FEATURED_SYMBOLS: FeaturedSymbol[] = [
  { symbol: "005930", label: "삼성전자", market: "KOSPI" },
  { symbol: "000660", label: "SK하이닉스", market: "KOSPI" },
  { symbol: "005380", label: "현대차", market: "KOSPI" },
  { symbol: "035420", label: "NAVER", market: "KOSPI" },
  { symbol: "AAPL", label: "AAPL", market: "NASDAQ" },
  { symbol: "NVDA", label: "NVDA", market: "NASDAQ" },
];

/** 기본 종목(첫 번째) — 삼성전자 */
export const DEFAULT_SYMBOL = FEATURED_SYMBOLS[0].symbol;

/**
 * 검색 결과에서 코드가 정확히 같은 종목의 id. [market]을 주면 시장도 같아야 한다. 없으면 null
 * (부분 일치는 쓰지 않는다 — 엉뚱한 종목보다 빈 편이 낫다).
 */
export async function findStockIdBySymbol(symbol: string, fetcher: typeof fetch = fetch, market?: string): Promise<number | null> {
  try {
    const r = await fetcher(`/api/stocks/search?query=${encodeURIComponent(symbol)}`);
    if (!r.ok) return null;
    const list: { id: number; symbol: string; market?: string }[] = await r.json();
    return list.find((s) => s.symbol.toUpperCase() === symbol.toUpperCase() && (market == null || s.market === market))?.id ?? null;
  } catch {
    return null;
  }
}

/** 코드 목록을 id로 바꾼다. 찾지 못한 종목은 빠진다(순서는 유지). */
export async function resolveFeaturedStocks(list: FeaturedSymbol[], fetcher: typeof fetch = fetch): Promise<FeaturedStock[]> {
  const ids = await Promise.all(list.map((s) => findStockIdBySymbol(s.symbol, fetcher, s.market)));
  return list.flatMap((s, i) => (ids[i] != null ? [{ ...s, id: ids[i]! }] : []));
}

/** 빠른 선택 종목(id 포함). 종목 id는 바뀌지 않으므로 한 번 찾으면 세션 동안 다시 묻지 않는다. */
export function useFeaturedStocks() {
  const q = useQuery({
    queryKey: ["stocks", "featured", FEATURED_SYMBOLS.map((s) => s.symbol)],
    queryFn: () => resolveFeaturedStocks(FEATURED_SYMBOLS),
    staleTime: Infinity,
    gcTime: Infinity,
  });
  const stocks = q.data ?? [];
  return { stocks, defaultId: stocks[0]?.id ?? null, isLoading: q.isLoading };
}
