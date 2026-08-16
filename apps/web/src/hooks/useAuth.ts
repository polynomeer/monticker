"use client";
import { useState, useEffect, useCallback } from "react";
import { getAccessToken, clearTokens, AUTH_CHANGED_EVENT } from "@/services/auth";

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

  const logout = () => {
    clearTokens();
    setIsLoggedIn(false);
    window.location.href = "/login";
  };

  return { isLoggedIn, setIsLoggedIn, logout };
}
