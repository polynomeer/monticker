import * as SecureStore from "./secureStore";
import { getApiBase } from "./api";

/**
 * ADR-109 — 네이티브 앱 인증. refresh token은 OS 보안 저장소(iOS Keychain·Android Keystore)에만 두고,
 * access token은 메모리에만 둔다(앱을 다시 열면 refresh로 새로 받는다).
 *
 * - 저장: WHEN_UNLOCKED_THIS_DEVICE_ONLY — 기기 잠금이 풀린 동안만 읽히고, 백업·다른 기기로 옮겨지지 않는다.
 * - 갱신: 서버가 매번 회전한다(쓴 토큰은 폐기). 동시에 여러 요청이 401을 받아도 갱신은 한 번만 한다 — 같은 토큰으로 두 번
 *   갱신하면 서버가 재사용으로 보고 모든 세션을 끊는다.
 * - 갱신이 401이면(폐기·만료·재사용 탐지) 저장소를 비우고 로그아웃 상태로 돌아간다.
 */

const REFRESH_KEY = "monticker.refreshToken";
const STORE_OPTIONS: SecureStore.SecureStoreOptions = { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY };

interface NativeTokenResponse {
  accessToken: string;
  refreshToken: string;
  refreshExpiresAt: string;
}

export type AuthState = "unknown" | "signedOut" | "signedIn";

let accessToken: string | null = null;
let state: AuthState = "unknown";
let refreshing: Promise<boolean> | null = null;
/** 로그아웃마다 올린다 — 로그아웃 전에 시작한 갱신이 끝난 뒤 토큰을 다시 저장하지 않게 */
let generation = 0;
const listeners = new Set<(s: AuthState) => void>();

function setState(next: AuthState) {
  state = next;
  listeners.forEach((l) => l(next));
}

export function getAuthState(): AuthState {
  return state;
}

export function subscribeAuth(listener: (s: AuthState) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

async function save(tokens: NativeTokenResponse, gen = generation) {
  if (gen !== generation) return;          // 그 사이 로그아웃했다 — 받은 토큰은 버린다(서버에서는 이미 폐기됐거나 곧 만료)
  await SecureStore.setItemAsync(REFRESH_KEY, tokens.refreshToken, STORE_OPTIONS);
  accessToken = tokens.accessToken;
  setState("signedIn");
}

async function clear() {
  accessToken = null;
  await SecureStore.deleteItemAsync(REFRESH_KEY, STORE_OPTIONS).catch(() => undefined);
  setState("signedOut");
}

export class LoginError extends Error {}

/** 이메일·비밀번호 로그인. 실패 사유는 서버가 구분해 주지 않는다(계정 존재 여부를 드러내지 않게) */
export async function login(email: string, password: string): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`${getApiBase()}/api/auth/native/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: email.trim(), password }),
    });
  } catch {
    throw new LoginError("서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.");
  }
  if (res.status === 429) throw new LoginError("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
  if (!res.ok) throw new LoginError("이메일 또는 비밀번호가 올바르지 않습니다.");
  await save(await res.json());
}

/** 저장된 refresh token으로 새 토큰 쌍을 받는다 — 동시에 불려도 요청은 하나 */
function refresh(): Promise<boolean> {
  if (!refreshing) {
    const gen = generation;
    refreshing = (async () => {
      try {
        // 기기가 잠겨 있으면(WHEN_UNLOCKED) 읽기가 실패한다 — 로그아웃시키지 않고 다음에 다시
        const stored = await SecureStore.getItemAsync(REFRESH_KEY, STORE_OPTIONS);
        if (!stored) { await clear(); return false; }
        const res = await fetch(`${getApiBase()}/api/auth/native/refresh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken: stored }),
        });
        if (res.status === 401 || res.status === 400) { if (gen === generation) await clear(); return false; }
        if (!res.ok) return false;          // 일시 오류(5xx·429) — 토큰은 그대로 두고 다음에 다시
        await save(await res.json(), gen);
        return gen === generation;
      } catch {
        return false;                        // 네트워크 오류·저장소 오류 — 로그아웃시키지 않는다
      }
    })().finally(() => { refreshing = null; });
  }
  return refreshing;
}

/** 앱을 열 때 — 저장된 토큰이 있으면 세션을 되살린다. 네트워크가 없으면 일단 로그인 상태로 둔다 */
export async function restoreSession(): Promise<void> {
  const stored = await SecureStore.getItemAsync(REFRESH_KEY, STORE_OPTIONS).catch(() => null);
  if (!stored) { setState("signedOut"); return; }
  const ok = await refresh();
  if (!ok && getAuthState() === "unknown") setState("signedIn");
}

/** 인증 요청 — access token이 없거나 401이면 한 번 갱신하고 다시 보낸다 */
export async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const send = () => fetch(`${getApiBase()}${path}`, {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
  });
  if (!accessToken) await refresh();
  let res = await send();
  if (res.status === 401 && (await refresh())) res = await send();
  return res;
}

/** 이 기기의 세션만 끝낸다. 서버 폐기가 실패해도 기기에서는 지운다 */
export async function logout(beforeClear?: () => Promise<void>): Promise<void> {
  // 진행 중인 갱신이 있으면 끝난 뒤에 — 그래야 지금 저장된(가장 새) 토큰을 폐기한다
  await refreshing?.catch(() => undefined);
  generation += 1;
  const stored = await SecureStore.getItemAsync(REFRESH_KEY, STORE_OPTIONS).catch(() => null);
  await beforeClear?.().catch(() => undefined);
  if (stored) {
    await fetch(`${getApiBase()}/api/auth/native/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: stored }),
    }).catch(() => undefined);
  }
  await clear();
}
