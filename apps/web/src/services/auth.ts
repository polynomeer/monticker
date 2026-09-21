const API = "";

export const AUTH_CHANGED_EVENT = "auth-changed";

function notifyAuthChanged() {
  if (typeof window !== "undefined") window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

// refreshToken은 응답 바디에 없다 — 서버가 HttpOnly 쿠키(Path=/api/auth)로만 내려준다.
// 모든 인증 fetch가 credentials:"include"를 쓰는 건 이 쿠키를 주고받기 위해서다.
export interface AuthTokens {
  accessToken: string;
}

export async function signup(email: string, password: string, nickname: string): Promise<AuthTokens> {
  const res = await fetch(`${API}/api/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ email, password, nickname }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function login(email: string, password: string): Promise<AuthTokens> {
  const res = await fetch(`${API}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error("이메일 또는 비밀번호가 올바르지 않습니다.");
  return res.json();
}

export async function refreshTokens(): Promise<AuthTokens> {
  const res = await fetch(`${API}/api/auth/refresh`, {
    method: "POST",
    credentials: "include",
  });
  if (!res.ok) throw new Error("세션이 만료되었습니다. 다시 로그인해 주세요.");
  return res.json();
}

export async function forgotPassword(email: string): Promise<string> {
  const res = await fetch(`${API}/api/auth/forgot-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.message ?? "요청에 실패했습니다.");
  return data?.message ?? "등록된 이메일이라면 재설정 링크를 발송했습니다.";
}

export async function resetPassword(token: string, newPassword: string): Promise<string> {
  const res = await fetch(`${API}/api/auth/reset-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, newPassword }),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.message ?? "비밀번호 재설정에 실패했습니다.");
  return data?.message ?? "비밀번호가 변경되었습니다.";
}

export function saveTokens(tokens: AuthTokens) {
  localStorage.setItem("accessToken", tokens.accessToken);
  notifyAuthChanged();
}

export function getAccessToken(): string | null {
  return typeof window !== "undefined" ? localStorage.getItem("accessToken") : null;
}

/** 로컬 저장소만 지운다 — 서버 쪽 refresh token은 살아있다. 실제 로그아웃은 logout()을 쓸 것. */
export function clearTokens() {
  localStorage.removeItem("accessToken");
  notifyAuthChanged();
}

/**
 * 서버의 refresh token을 먼저 폐기하고 로컬 저장소를 지운다. 순서가 중요하다 — 로컬만 지우고
 * 서버 폐기를 건너뛰면, 그 refresh token이 (탈취됐을 경우) "로그아웃"과 무관하게 계속 유효하다
 * (docs/security-review.md C2).
 */
export async function logout(): Promise<void> {
  try {
    await fetch(`${API}/api/auth/logout`, { method: "POST", credentials: "include" });
  } finally {
    clearTokens();
  }
}
