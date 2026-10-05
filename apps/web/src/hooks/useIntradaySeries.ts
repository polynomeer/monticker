"use client";

import { useQueries, useQuery } from "@tanstack/react-query";

/** GET /api/market/intraday 응답 한 건 — 10분 버킷 종가(오래된 → 최근) */
export interface IntradaySeries {
  stockId: number;
  closes: number[];
  lastTime: string | null;
}

/**
 * 여러 종목의 장중 미니 시계열(스크리너·관심종목 "오늘" 열). 서버가 50개까지만 받고 30초 캐시한다.
 * 키는 정렬한 id 목록이라 같은 종목 집합이면 화면이 달라도 요청 하나를 같이 쓴다.
 */
function intradayQuery(ids: number[], enabled = true) {
  return {
    queryKey: ["market", "intraday", ids],
    queryFn: async (): Promise<IntradaySeries[]> => {
      const r = await fetch(`/api/market/intraday?ids=${ids.join(",")}`);
      return r.ok ? r.json() : [];
    },
    enabled: enabled && ids.length > 0,
    refetchInterval: 60_000,
    staleTime: 30_000,
  };
}

const MAX_PER_REQUEST = 50;

export function useIntradaySeries(stockIds: number[], enabled = true) {
  const ids = Array.from(new Set(stockIds)).filter((id) => id > 0).sort((a, b) => a - b).slice(0, MAX_PER_REQUEST);
  const { data = [] } = useQuery(intradayQuery(ids, enabled));
  return new Map(data.map((s) => [s.stockId, s.closes]));
}

/**
 * 무한 스크롤 목록용 — 불러온 순서대로 50개씩 나눠 요청한다. 앞쪽 묶음의 키는 뒤에 행이 더 붙어도
 * 바뀌지 않아 이미 받은 시계열을 다시 받지 않는다.
 */
export function useIntradaySeriesChunked(stockIds: number[], enabled = true) {
  const unique = Array.from(new Set(stockIds)).filter((id) => id > 0);
  const chunks: number[][] = [];
  for (let i = 0; i < unique.length; i += MAX_PER_REQUEST) chunks.push(unique.slice(i, i + MAX_PER_REQUEST).sort((a, b) => a - b));
  const results = useQueries({ queries: chunks.map((c) => intradayQuery(c, enabled)) });
  const out = new Map<number, number[]>();
  results.forEach((r) => (r.data ?? []).forEach((s) => out.set(s.stockId, s.closes)));
  return out;
}
