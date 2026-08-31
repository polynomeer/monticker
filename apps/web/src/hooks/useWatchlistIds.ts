"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";

interface WatchlistItem { id: number; stockId: number; }
interface WatchlistGroup { id: number; items: WatchlistItem[]; }

/**
 * 관심종목 여부를 빠르게 조회/토글하기 위한 훅.
 * 스크리너 등 목록 화면에서 행 단위로 별표 토글할 때 사용 — 그룹은 항상 첫 번째 그룹을 사용한다.
 */
export function useWatchlistIds() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);
  const qc = useQueryClient();

  const { data: groups = [] } = useQuery<WatchlistGroup[]>({
    queryKey: ["watchlist", "groups"],
    queryFn: async () => {
      const r = await authFetch("/api/watchlists");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
    staleTime: 15_000,
  });

  const itemIdByStockId = new Map<number, number>();
  groups.forEach(g => g.items.forEach(i => itemIdByStockId.set(i.stockId, i.id)));
  const defaultGroupId = groups[0]?.id ?? null;

  const add = useMutation({
    mutationFn: async (stockId: number) => {
      if (!defaultGroupId) throw new Error("먼저 관심종목 그룹을 만들어주세요.");
      const res = await authFetch(`/api/watchlists/groups/${defaultGroupId}/items`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId }),
      });
      if (!res.ok && res.status !== 409) throw new Error("관심종목 추가 실패");
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["watchlist"] }),
  });

  const remove = useMutation({
    mutationFn: async (itemId: number) => {
      await authFetch(`/api/watchlists/items/${itemId}`, { method: "DELETE" });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["watchlist"] }),
  });

  const toggle = (stockId: number) => {
    const itemId = itemIdByStockId.get(stockId);
    if (itemId) remove.mutate(itemId);
    else add.mutate(stockId);
  };

  return {
    isLoggedIn,
    hasGroup: defaultGroupId != null,
    isWatched: (stockId: number) => itemIdByStockId.has(stockId),
    toggle,
    pending: add.isPending || remove.isPending,
  };
}
