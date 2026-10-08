import { authFetch } from "./api";

/** 서버 enum(UserPreferenceService.InterestSector/UsageStyle, V86 CHECK)과 같은 값. 라벨은 화면에서만 쓴다. */
export type InterestSector =
  | "SEMICONDUCTOR" | "SECONDARY_BATTERY" | "INTERNET_PLATFORM" | "BIO" | "FINANCE"
  | "AUTOMOTIVE" | "DIVIDEND" | "ETF" | "SHIPBUILDING_DEFENSE";
export type UsageStyle = "OBSERVE" | "EVENT_TRADING" | "QUANT";

export interface UserPreferences {
  interestSectors: InterestSector[];
  usageStyle: UsageStyle | null;
  updatedAt: string | null;
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
