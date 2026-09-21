"use client";
import { useState, useEffect, useCallback } from "react";
import { getAccessToken, logout as logoutRequest, AUTH_CHANGED_EVENT } from "@/services/auth";

export function useAuth() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  const sync = useCallback(() => setIsLoggedIn(!!getAccessToken()), []);

  useEffect(() => {
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
