"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchPreferences, saveInterestOrdering, savePreferences, type UserPreferences, type UsageStyle } from "@/services/onboarding";
import { interestOrderingActive, type InterestSector } from "@/lib/interestSectors";

export const USER_PREFERENCES_KEY = ["users", "me", "preferences"] as const;

/** GET /api/users/me/preferences — 로그인했을 때만. 실패하면 null(화면은 관심 분야 없음과 같게 동작). */
export function useUserPreferences(enabled: boolean) {
  return useQuery<UserPreferences | null>({
    queryKey: USER_PREFERENCES_KEY,
    queryFn: fetchPreferences,
    enabled,
    staleTime: 60_000,
  });
}

/**
 * 홈·알림이 쓰는 관심 분야 정렬 상태(ADR-099).
 * - interests: 고른 관심 분야(없으면 빈 목록)
 * - available: 관심 분야가 있어 스위치를 보여 줄지
 * - active: 지금 관심 분야 순으로 정렬·강조하는지(관심 분야가 있고 스위치가 켜짐)
 * - setEnabled: 스위치를 바꾼다 — 캐시를 먼저 바꾸고(낙관적) 실패하면 되돌린다
 */
export function useInterestOrdering(isLoggedIn: boolean) {
  const qc = useQueryClient();
  const { data: prefs } = useUserPreferences(isLoggedIn);
  const interests: InterestSector[] = (isLoggedIn && prefs?.interestSectors) || [];

  const mutation = useMutation({
    mutationFn: saveInterestOrdering,
    onMutate: async (next: boolean) => {
      await qc.cancelQueries({ queryKey: USER_PREFERENCES_KEY });
      const prev = qc.getQueryData<UserPreferences | null>(USER_PREFERENCES_KEY);
      if (prev) qc.setQueryData<UserPreferences>(USER_PREFERENCES_KEY, { ...prev, interestOrdering: next });
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev !== undefined) qc.setQueryData(USER_PREFERENCES_KEY, ctx.prev);
    },
    onSuccess: (saved) => qc.setQueryData(USER_PREFERENCES_KEY, saved),
  });

  return {
    interests,
    available: interests.length > 0,
    active: isLoggedIn && interestOrderingActive(prefs),
    setEnabled: (v: boolean) => mutation.mutate(v),
    pending: mutation.isPending,
    error: mutation.error as Error | null,
  };
}

/** 설정 화면의 관심 분야·사용 방식 저장(PUT). 성공하면 캐시를 서버 응답으로 바꾼다. */
export function useSavePreferences() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ sectors, style }: { sectors: InterestSector[]; style: UsageStyle | null }) => savePreferences(sectors, style),
    onSuccess: (saved) => qc.setQueryData(USER_PREFERENCES_KEY, saved),
  });
}
