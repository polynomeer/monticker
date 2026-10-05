"use client";

import { useRouter } from "next/navigation";
import { saveTokens } from "@/services/auth";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const IS_MOCK = process.env.NEXT_PUBLIC_SOCIAL_MOCK === "true";

/** 백엔드(application.yml spring.security.oauth2.client.registration)에 등록된 공급자만 */
const PROVIDERS = [
  { id: "google", mock: "GOOGLE", label: "Google" },
  { id: "kakao", mock: "KAKAO", label: "카카오" },
  { id: "naver", mock: "NAVER", label: "네이버" },
] as const;

const BTN =
  "flex h-[46px] min-w-[110px] flex-1 items-center justify-center gap-2 rounded-[10px] border border-tm-line2 bg-transparent text-sm font-medium text-dracula-fg hover:bg-tm-raised hover:text-dracula-fg";

async function mockSocialLogin(provider: string): Promise<{ accessToken: string }> {
  const res = await fetch(`${API_URL}/api/auth/mock-social?provider=${provider}`, { method: "POST", credentials: "include" });
  if (!res.ok) throw new Error("Mock 소셜 로그인 실패");
  return res.json();
}

/**
 * 소셜 로그인(가입 겸용) 버튼 줄. 운영에서는 백엔드 OAuth2 엔드포인트로 이동하고,
 * NEXT_PUBLIC_SOCIAL_MOCK=true 로컬 개발에서는 리다이렉트 없이 mock 토큰을 받는다.
 */
export function SocialButtons({ onError }: { onError?: (msg: string) => void }) {
  const router = useRouter();

  if (IS_MOCK) {
    const handle = async (provider: string, label: string) => {
      try {
        const tokens = await mockSocialLogin(provider);
        saveTokens(tokens);
        router.push("/");
      } catch {
        onError?.(`${label} Mock 로그인 실패`);
      }
    };
    return (
      <div className="flex flex-wrap gap-2">
        {PROVIDERS.map((p) => (
          <button key={p.id} type="button" onClick={() => handle(p.mock, p.label)} className={BTN}>
            <span className="num rounded bg-tm-raised px-1 text-2xs text-tm-muted">DEV</span>
            {p.label} (Mock)
          </button>
        ))}
      </div>
    );
  }

  return (
    <div className="flex flex-wrap gap-2">
      {PROVIDERS.map((p) => (
        <a key={p.id} href={`${API_URL}/oauth2/authorization/${p.id}`} className={BTN}>
          {p.label}로 계속
        </a>
      ))}
    </div>
  );
}
