"use client";
import Link from "next/link";
import { useAuth } from "@/hooks/useAuth";

export default function AuthNav() {
  const { isLoggedIn, logout } = useAuth();

  if (isLoggedIn) {
    return (
      <button
        onClick={logout}
        className="text-sm font-medium text-gray-600 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg transition-colors duration-300 ease-spring"
      >
        로그아웃
      </button>
    );
  }
  return (
    <Link
      href="/login"
      className="text-sm font-medium px-4 py-1.5 rounded-full bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg hover:opacity-90 hover:scale-[1.03] active:scale-[0.97] transition-all duration-300 ease-spring"
    >
      로그인
    </Link>
  );
}
