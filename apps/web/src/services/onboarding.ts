import { authFetch } from "./api";

import type { InterestSector } from "@/lib/interestSectors";

/** 서버 enum(UserPreferenceService.InterestSector/UsageStyle, V86 CHECK)과 같은 값. 라벨·업종 매핑은 lib/interestSectors(ADR-099). */
export type { InterestSector };
export type UsageStyle = "OBSERVE" | "EVENT_TRADING" | "QUANT";

export interface UserPreferences {
  interestSectors: InterestSector[];
  usageStyle: UsageStyle | null;
  updatedAt: string | null;
  /** ADR-099 — 홈·알림의 "관심 분야 순" 스위치(기본 true). 관심 분야가 없으면 화면이 무시한다. 이전 서버 응답엔 없을 수 있다. */
  interestOrdering?: boolean;
}

/** ADR-089 — 서버 화이트리스트(PaperInitialCapital)와 같은 값. */
export const PAPER_INITIAL_CAPITALS = [10_000_000, 30_000_000, 100_000_000] as const;
export type PaperInitialCapital = (typeof PAPER_INITIAL_CAPITALS)[number];

export interface PaperAccountInfo { initialCapital: number; cash: number; created: boolean }

async function errorMessage(r: Response, fallback: string) {
  try {
    const body = await r.json();
    return typeof body?.message === "string" ? body.message : fallback;
  } catch {
    return fallback;
  }
}

export async function fetchPreferences(): Promise<UserPreferences | null> {
  const r = await authFetch("/api/users/me/preferences");
  return r.ok ? r.json() : null;
}

export async function savePreferences(interestSectors: InterestSector[], usageStyle: UsageStyle | null): Promise<UserPreferences> {
  const r = await authFetch("/api/users/me/preferences", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ interestSectors, usageStyle }),
  });
  if (!r.ok) throw new Error(await errorMessage(r, "관심 분야를 저장하지 못했습니다."));
  return r.json();
}

/** ADR-099 — "관심 분야 순" 스위치만 바꾼다(관심 분야·사용 방식은 그대로). */
export async function saveInterestOrdering(interestOrdering: boolean): Promise<UserPreferences> {
  const r = await authFetch("/api/users/me/preferences", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ interestOrdering }),
  });
  if (!r.ok) throw new Error(await errorMessage(r, "설정을 저장하지 못했습니다."));
  return r.json();
}

/** 내 모의 계좌 — 아직 없으면 null(204). */
export async function fetchPaperAccount(): Promise<PaperAccountInfo | null> {
  const r = await authFetch("/api/paper/account");
  if (r.status === 204 || !r.ok) return null;
  return r.json();
}

/** 시작 자금으로 모의 계좌를 처음 만든다. 같은 값이면 멱등(200), 이미 다른 값의 계좌가 있으면 409(메시지 그대로). */
export async function openPaperAccount(initialCapital: PaperInitialCapital): Promise<PaperAccountInfo> {
  const r = await authFetch("/api/paper/account", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ initialCapital }),
  });
  if (!r.ok) throw new Error(await errorMessage(r, "모의 계좌를 만들지 못했습니다."));
  return r.json();
}
