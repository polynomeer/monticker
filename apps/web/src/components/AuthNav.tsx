"use client";
import Link from "next/link";
import { useAuth } from "@/hooks/useAuth";

export default function AuthNav() {
  const { isLoggedIn, logout } = useAuth();

  if (isLoggedIn) {
    return (
      <button
        onClick={logout}
        className="text-sm font-medium text-gray-600 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg transition-colors"
      >
        로그아웃
      </button>
    );
  }
  return (
    <Link
      href="/login"
      className="text-sm font-medium px-3 py-1.5 rounded-md bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg hover:opacity-90 active:scale-[0.98] transition-all duration-150"
    >
      로그인
    </Link>
  );
}
