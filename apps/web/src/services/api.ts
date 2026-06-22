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

  // 401이면 refresh 시도
  if (response.status === 401 && !isRefreshing) {
    const refreshToken = typeof window !== "undefined" ? localStorage.getItem("refreshToken") : null;
    if (!refreshToken) {
      clearTokens();
      return response;
    }

    isRefreshing = true;
    try {
      const newTokens = await refreshTokens(refreshToken);
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
