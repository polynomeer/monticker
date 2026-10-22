import { useEffect, useState } from "react";
import { getAuthState, subscribeAuth, type AuthState } from "./auth";

/** 로그인 상태 — "unknown"은 앱을 연 직후 저장된 세션을 확인하는 동안 */
export function useAuthState(): AuthState {
  const [state, setState] = useState<AuthState>(getAuthState());
  useEffect(() => subscribeAuth(setState), []);
  return state;
}
