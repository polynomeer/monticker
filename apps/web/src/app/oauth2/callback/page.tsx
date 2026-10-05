"use client";
import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { saveTokens } from "@/services/auth";
import { CenteredPage, StatusCard } from "@/components/auth/StatusCard";

function OAuth2CallbackContent() {
  const router = useRouter();
  const params = useSearchParams();

  useEffect(() => {
    // refreshToken은 URL에 없다 — 서버가 리다이렉트 응답에 HttpOnly 쿠키로 이미 실어 보냈다
    // (docs/security-review.md C2). accessToken만 짧은 수명(15분)이라 URL로 받는다.
    const accessToken = params.get("accessToken");
    const error = params.get("error");

    if (error || !accessToken) {
      router.replace("/login?error=oauth2");
      return;
    }

    saveTokens({ accessToken });
    router.replace("/");
  }, [params, router]);

  return (
    <StatusCard tone="pending" title="로그인 중...">
      <p className="m-0">소셜 계정 확인이 끝나면 자동으로 이동합니다.</p>
    </StatusCard>
  );
}

export default function OAuth2CallbackPage() {
  return (
    <CenteredPage>
      <Suspense fallback={null}>
        <OAuth2CallbackContent />
      </Suspense>
    </CenteredPage>
  );
}
