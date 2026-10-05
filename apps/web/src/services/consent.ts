import { authFetch } from "./api";

/** ADR-068 — 동의 항목. 서버 ConsentType과 같은 이름. */
export type ConsentType =
  | "TERMS" | "PRIVACY" | "AGE_OVER_19" | "MARKETING"
  | "BROKERAGE_DELEGATION" | "BROKERAGE_NO_CUSTODY" | "BROKERAGE_LOSS_ATTRIBUTION";

export interface ConsentState {
  type: ConsentType;
  agreed: boolean;
  documentVersion: string | null;
  currentVersion: string;
  recordedAt: string | null;
}

export interface ConsentStatus {
  states: ConsentState[];
  /** 지금 받아야 하는 가입 필수 동의 — 소셜 가입이거나 약관이 개정됐다 */
  missingRequired: ConsentType[];
}

async function read(res: Response): Promise<ConsentStatus> {
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? "동의 상태를 처리하지 못했습니다.");
  }
  return res.json();
}

export async function getConsentStatus(): Promise<ConsentStatus> {
  return read(await authFetch("/api/users/me/consents"));
}

export async function agreeConsents(consents: ConsentType[]): Promise<ConsentStatus> {
  return read(await authFetch("/api/users/me/consents", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ consents }),
  }));
}

export async function withdrawConsent(type: ConsentType): Promise<ConsentStatus> {
  return read(await authFetch(`/api/users/me/consents/${type}`, { method: "DELETE" }));
}

/** 동의 화면에서 돌아갈 곳 — 앱 안의 상대 경로만 허용한다(열린 리다이렉트 방지). */
export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) return "/";
  return next;
}
