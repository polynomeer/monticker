"use client";
import { useState, useEffect, useCallback } from "react";
import { getAccessToken, logout as logoutRequest, AUTH_CHANGED_EVENT } from "@/services/auth";

/** 첫 화면의 hydration이 끝났는지. 끝나기 전에는 서버 렌더와 맞춰 false로 시작해야 한다 */
let hydrated = false;

export function useAuth() {
  // 이후 마운트는 토큰을 바로 읽는다. false로 시작하면 셸이 다시 마운트될 때마다 enabled가 false → true로 바뀌어
  // 쿼리가 무조건 다시 조회된다 — 실패한 조회라면 "로딩 ↔ 오류 화면"이 번갈아 초당 한 번씩 요청이 나간다.
  const [isLoggedIn, setIsLoggedIn] = useState(() => hydrated && !!getAccessToken());

  const sync = useCallback(() => setIsLoggedIn(!!getAccessToken()), []);

  useEffect(() => {
    hydrated = true;
    sync();
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, [sync]);

  // 서버 쪽 refresh token 폐기까지 기다린 뒤 이동한다 — 로그아웃 버튼을 눌렀는데 refresh
  // token이 서버에 살아있는 채로 남는 걸 막는다 (docs/security-review.md C2).
  const logout = () => {
    logoutRequest().finally(() => {
      setIsLoggedIn(false);
      window.location.href = "/login";
    });
  };

  return { isLoggedIn, setIsLoggedIn, logout };
}
