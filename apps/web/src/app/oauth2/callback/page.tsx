"use client";
import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { saveTokens } from "@/services/auth";

export default function OAuth2CallbackPage() {
  const router = useRouter();
  const params = useSearchParams();

  useEffect(() => {
    const accessToken  = params.get("accessToken");
    const refreshToken = params.get("refreshToken");
    const error        = params.get("error");

    if (error || !accessToken || !refreshToken) {
      router.replace("/login?error=oauth2");
      return;
    }

    saveTokens({ accessToken, refreshToken });
    router.replace("/");
  }, [params, router]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-[#282a36]">
      <div className="flex flex-col items-center gap-3">
        <div className="w-8 h-8 border-4 border-[#bd93f9] border-t-transparent rounded-full animate-spin" />
        <p className="text-sm text-gray-500 dark:text-[#6272a4]">로그인 중...</p>
      </div>
    </div>
  );
}
