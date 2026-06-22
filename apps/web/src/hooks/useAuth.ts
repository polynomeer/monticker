"use client";
import { useState, useEffect } from "react";
import { getAccessToken, clearTokens } from "@/services/auth";

export function useAuth() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => {
    setIsLoggedIn(!!getAccessToken());
  }, []);

  const logout = () => {
    clearTokens();
    setIsLoggedIn(false);
    window.location.href = "/login";
  };

  return { isLoggedIn, setIsLoggedIn, logout };
}
