const API = "";

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}

export async function signup(email: string, password: string, nickname: string): Promise<AuthTokens> {
  const res = await fetch(`${API}/api/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, nickname }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export async function login(email: string, password: string): Promise<AuthTokens> {
  const res = await fetch(`${API}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error("이메일 또는 비밀번호가 올바르지 않습니다.");
  return res.json();
}

export async function refreshTokens(refreshToken: string): Promise<AuthTokens> {
  const res = await fetch(`${API}/api/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
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
  localStorage.setItem("refreshToken", tokens.refreshToken);
}

export function getAccessToken(): string | null {
  return typeof window !== "undefined" ? localStorage.getItem("accessToken") : null;
}

export function clearTokens() {
  localStorage.removeItem("accessToken");
  localStorage.removeItem("refreshToken");
}
