"use client";

import { useQuery } from "@tanstack/react-query";
import type { UserProfile } from "@monticker/types";
import { authFetch } from "@/services/api";

/** GET /api/users/me — 계정 메뉴의 닉네임·이메일. 메뉴를 열 때만 읽는다 */
export function useUserProfile(enabled: boolean) {
  return useQuery<UserProfile>({
    queryKey: ["users", "me"],
    queryFn: async () => {
      const r = await authFetch("/api/users/me");
      if (!r.ok) throw new Error("프로필 조회 실패");
      return r.json();
    },
    enabled,
    staleTime: 5 * 60_000,
    retry: false,
  });
}
