import { getAccessToken, clearTokens, saveTokens, refreshTokens } from "./auth";

/**
 * 진행 중인 토큰 재발급 하나를 모든 요청이 같이 기다린다(single-flight).
 * 이전엔 첫 401만 재발급·재시도하고 그사이 들어온 401은 그대로 반환해, 요청을 여러 개 동시에 보내는 화면(지갑 등)이
 * 토큰 만료 순간 대부분 "불러오지 못했습니다"가 됐다. 재발급을 동시에 여러 번 부르면 refresh 토큰 회전이 서로를
 * 무효로 만들 수도 있다.
 */
let refreshing: Promise<string | null> | null = null;

function refreshOnce(): Promise<string | null> {
  if (!refreshing) {
    refreshing = refreshTokens()
      .then((tokens) => {
        saveTokens(tokens);
        return tokens.accessToken;
      })
      .catch(() => {
        clearTokens();
        return null;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

function withToken(init: RequestInit, token: string | null): RequestInit {
  return {
    ...init,
    headers: {
      ...(init.headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  };
}

export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const sent = getAccessToken();
  const response = await fetch(input, withToken(init, sent));
  if (response.status !== 401) return response;

  // 401이면 refresh 시도 — refreshToken은 HttpOnly 쿠키로만 오가므로 여기서 직접 읽을 수
  // 없다. 쿠키가 아예 없으면(비로그인) refreshTokens()가 401을 던지고 원래 응답을 돌려준다.
  // 이 요청이 나간 뒤 다른 요청이 이미 재발급을 끝냈으면 새 토큰으로 바로 다시 보낸다.
  const current = getAccessToken();
  const token = current && current !== sent ? current : await refreshOnce();
  if (!token) return response;
  return fetch(input, withToken(init, token));
}
