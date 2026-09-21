import { getAccessToken, clearTokens, saveTokens, refreshTokens } from "./auth";

let isRefreshing = false;

async function getValidToken(): Promise<string | null> {
  const token = getAccessToken();
  if (!token) return null;
  return token;
}

export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const token = await getValidToken();

  const response = await fetch(input, {
    ...init,
    headers: {
      ...(init.headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });

  // 401이면 refresh 시도 — refreshToken은 HttpOnly 쿠키로만 오가므로 여기서 직접 읽을 수
  // 없다. 쿠키가 아예 없으면(비로그인) refreshTokens()가 401을 던지고 catch로 빠진다.
  if (response.status === 401 && !isRefreshing) {
    isRefreshing = true;
    try {
      const newTokens = await refreshTokens();
      saveTokens(newTokens);
      isRefreshing = false;

      // retry with new token
      return fetch(input, {
        ...init,
        headers: {
          ...(init.headers ?? {}),
          Authorization: `Bearer ${newTokens.accessToken}`,
        },
      });
    } catch {
      isRefreshing = false;
      clearTokens();
      return response;
    }
  }

  return response;
}
