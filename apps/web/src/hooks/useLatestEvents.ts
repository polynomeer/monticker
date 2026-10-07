"use client";

import { useQuery } from "@tanstack/react-query";
import type { StockEvent } from "@monticker/types";
import { authFetch } from "@/services/api";

/** 서버 상한(EventTimelineController.LATEST_MAX_STOCKS)과 같다 */
const MAX_STOCKS = 100;

/**
 * 종목별 가장 최근 이벤트 1건씩 — `GET /api/events/latest?stockIds=…` 한 번(종목마다 타임라인을 부르지 않는다).
 * 이벤트가 없는 종목은 Map에 없다.
 */
export function useLatestEvents(stockIds: number[]) {
  const ids = [...new Set(stockIds)].sort((a, b) => a - b).slice(0, MAX_STOCKS);
  return useQuery<Map<number, StockEvent>>({
    queryKey: ["events", "latest", ids.join(",")],
    queryFn: async () => {
      const r = await authFetch(`/api/events/latest?stockIds=${ids.join(",")}`);
      if (!r.ok) throw new Error("최근 이벤트 조회 실패");
      const list: StockEvent[] = await r.json();
      return new Map(list.map((e) => [e.stockId, e]));
    },
    enabled: ids.length > 0,
    staleTime: 60_000,
  });
}
