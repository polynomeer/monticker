import { getAccessToken } from "./auth";

export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const token = getAccessToken();
  return fetch(input, {
    ...init,
    headers: {
      ...(init.headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
}
