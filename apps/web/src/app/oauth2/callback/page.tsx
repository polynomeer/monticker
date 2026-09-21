"use client";
import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { saveTokens } from "@/services/auth";

export default function OAuth2CallbackPage() {
  const router = useRouter();
  const params = useSearchParams();

  useEffect(() => {
    // refreshToken은 URL에 없다 — 서버가 리다이렉트 응답에 HttpOnly 쿠키로 이미 실어 보냈다
    // (docs/security-review.md C2). accessToken만 짧은 수명(15분)이라 URL로 받는다.
    const accessToken = params.get("accessToken");
    const error       = params.get("error");

    if (error || !accessToken) {
      router.replace("/login?error=oauth2");
      return;
    }

    saveTokens({ accessToken });
    router.replace("/");
  }, [params, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-dracula-bg bg-mesh-light dark:bg-mesh-dark">
      <div className="flex flex-col items-center gap-3 animate-fade-up">
        <div className="w-8 h-8 border-4 border-dracula-purple border-t-transparent rounded-full animate-spin" />
        <p className="text-sm text-gray-500 dark:text-dracula-comment">로그인 중...</p>
      </div>
    </div>
  );
}
