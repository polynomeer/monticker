"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { fromServer, type ScreenerCriteria } from "./criteria";

/** /api/screener/saved 한 건(ADR-072) */
export interface SavedScreen {
  id: number;
  name: string;
  criteria: ScreenerCriteria;
  createdAt: string;
  updatedAt: string;
}

const KEY = ["screener", "saved"];

async function readError(r: Response) {
  const body = await r.json().catch(() => null);
  return new Error(body?.message ?? `요청이 실패했습니다 (${r.status})`);
}

export function useSavedScreens(enabled: boolean) {
  return useQuery<SavedScreen[]>({
    queryKey: KEY,
    queryFn: async () => {
      const r = await authFetch("/api/screener/saved");
      if (!r.ok) return [];
      const list: SavedScreen[] = await r.json();
      return list.map((s) => ({ ...s, criteria: fromServer(s.criteria) }));
    },
    enabled,
    staleTime: 60_000,
  });
}

/** 저장(id 없으면 새로, 있으면 덮어쓰기)·삭제 */
export function useSavedScreenMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: KEY });

  const save = useMutation({
    mutationFn: async ({ id, name, criteria }: { id?: number; name: string; criteria: ScreenerCriteria }) => {
      const r = await authFetch(id == null ? "/api/screener/saved" : `/api/screener/saved/${id}`, {
        method: id == null ? "POST" : "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, criteria }),
      });
      if (!r.ok) throw await readError(r);
      return (await r.json()) as SavedScreen;
    },
    onSuccess: invalidate,
  });

  const remove = useMutation({
    mutationFn: async (id: number) => {
      const r = await authFetch(`/api/screener/saved/${id}`, { method: "DELETE" });
      if (!r.ok && r.status !== 404) throw await readError(r);
    },
    onSuccess: invalidate,
  });

  return { save, remove };
}
