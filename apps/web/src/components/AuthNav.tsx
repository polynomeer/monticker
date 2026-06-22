"use client";
import Link from "next/link";
import { useAuth } from "@/hooks/useAuth";

export default function AuthNav() {
  const { isLoggedIn, logout } = useAuth();

  if (isLoggedIn) {
    return (
      <button onClick={logout} className="text-sm text-gray-600 hover:text-gray-900">
        로그아웃
      </button>
    );
  }
  return (
    <Link href="/login" className="text-sm text-gray-600 hover:text-gray-900">
      로그인
    </Link>
  );
}
