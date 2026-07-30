"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { login, saveTokens } from "@/services/auth";
import Link from "next/link";

const API_URL  = process.env.NEXT_PUBLIC_API_URL  || "http://localhost:8080";
const IS_MOCK  = process.env.NEXT_PUBLIC_SOCIAL_MOCK === "true";

function SocialButton({
  href,
  label,
  icon,
  bg,
  textColor = "text-white",
}: {
  href: string;
  label: string;
  icon: React.ReactNode;
  bg: string;
  textColor?: string;
}) {
  return (
    <a
      href={href}
      className={`flex items-center justify-center gap-2 w-full py-2 rounded-lg font-medium text-sm transition-opacity hover:opacity-90 ${bg} ${textColor}`}
    >
      {icon}
      {label}
    </a>
  );
}

async function mockSocialLogin(provider: string): Promise<{ accessToken: string; refreshToken: string }> {
  const res = await fetch(
    `${API_URL}/api/auth/mock-social?provider=${provider}`,
    { method: "POST" },
  );
  if (!res.ok) throw new Error("Mock 소셜 로그인 실패");
  return res.json();
}

export default function LoginPage() {
  const router = useRouter();
  const [email,    setEmail]    = useState("");
  const [password, setPassword] = useState("");
  const [errors,   setErrors]   = useState<Record<string, string>>({});
  const [loading,  setLoading]  = useState(false);

  const handleMockSocial = async (provider: string) => {
    try {
      const tokens = await mockSocialLogin(provider);
      saveTokens(tokens);
      router.push("/");
    } catch {
      setErrors({ form: `${provider} Mock 로그인 실패` });
    }
  };

  const validate = () => {
    const e: Record<string, string> = {};
    if (!email)    e.email    = "이메일을 입력해주세요.";
    if (!password) e.password = "비밀번호를 입력해주세요.";
    return e;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errs = validate();
    if (Object.keys(errs).length) { setErrors(errs); return; }
    setErrors({});
    setLoading(true);
    try {
      const tokens = await login(email, password);
      saveTokens(tokens);
      router.push("/");
    } catch (err) {
      setErrors({ form: err instanceof Error ? err.message : "이메일 또는 비밀번호가 올바르지 않습니다." });
    } finally {
      setLoading(false);
    }
  };

  const inputCls = (field: string) =>
    `border rounded-lg px-4 py-2 w-full focus:outline-none focus:ring-2 dark:bg-[#44475a] dark:text-[#f8f8f2] dark:placeholder-[#6272a4] ${
      errors[field]
        ? "border-[#ff5555] focus:ring-[#ff5555]/50"
        : "border-gray-300 dark:border-[#44475a] focus:ring-[#bd93f9]/50"
    }`;

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-[#282a36]">
      <div className="bg-white dark:bg-[#21222c] dark:border-[#44475a] p-8 rounded-xl shadow-sm border border-gray-200 w-full max-w-sm">
        <h1 className="text-2xl font-bold mb-6 text-center dark:text-[#f8f8f2]">로그인</h1>

        {/* 소셜 로그인 */}
        <div className="flex flex-col gap-2 mb-5">
          {IS_MOCK ? (
            // 로컬 개발 mock — OAuth2 리다이렉트 없이 바로 로그인
            <>
              {[
                { provider: "GOOGLE", label: "Google (Mock)", bg: "bg-white border border-gray-300 dark:bg-[#44475a] dark:border-[#6272a4]", textColor: "text-gray-700 dark:text-[#f8f8f2]" },
                { provider: "KAKAO",  label: "카카오 (Mock)", bg: "bg-[#FEE500]", textColor: "text-[#191919]" },
                { provider: "NAVER",  label: "네이버 (Mock)", bg: "bg-[#03C75A]", textColor: "text-white" },
              ].map(({ provider, label, bg, textColor }) => (
                <button
                  key={provider}
                  type="button"
                  onClick={() => handleMockSocial(provider)}
                  className={`flex items-center justify-center gap-2 w-full py-2 rounded-lg font-medium text-sm transition-opacity hover:opacity-90 ${bg} ${textColor}`}
                >
                  <span className="text-xs font-mono bg-black/10 px-1 rounded">DEV</span>
                  {label}
                </button>
              ))}
            </>
          ) : (
            // 프로덕션 — 실제 OAuth2 provider로 리다이렉트
            <>
              <SocialButton
                href={`${API_URL}/oauth2/authorization/google`}
                label="Google로 계속하기"
                bg="bg-white border border-gray-300 dark:bg-[#44475a] dark:border-[#6272a4]"
                textColor="text-gray-700 dark:text-[#f8f8f2]"
                icon={
                  <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                    <path fill="#4285F4" d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844a4.14 4.14 0 0 1-1.796 2.716v2.259h2.908c1.702-1.567 2.684-3.875 2.684-6.615z"/>
                    <path fill="#34A853" d="M9 18c2.43 0 4.467-.806 5.956-2.184l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 0 0 9 18z"/>
                    <path fill="#FBBC05" d="M3.964 10.706A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.706V4.962H.957A8.996 8.996 0 0 0 0 9c0 1.452.348 2.827.957 4.038l3.007-2.332z"/>
                    <path fill="#EA4335" d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.962L3.964 7.294C4.672 5.163 6.656 3.58 9 3.58z"/>
                  </svg>
                }
              />
              <SocialButton
                href={`${API_URL}/oauth2/authorization/kakao`}
                label="카카오로 계속하기"
                bg="bg-[#FEE500]"
                textColor="text-[#191919]"
                icon={
                  <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                    <path fill="#191919" d="M9 1C4.582 1 1 3.79 1 7.226c0 2.183 1.398 4.1 3.508 5.21l-.895 3.327a.25.25 0 0 0 .383.274L8.16 13.4c.276.027.556.041.84.041 4.418 0 8-2.79 8-6.226S13.418 1 9 1z"/>
                  </svg>
                }
              />
              <SocialButton
                href={`${API_URL}/oauth2/authorization/naver`}
                label="네이버로 계속하기"
                bg="bg-[#03C75A]"
                icon={
                  <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                    <path fill="white" d="M10.53 9.27 7.23 1H1v16h6.47l3.3-8.27L13.77 17H18V1h-7.47z"/>
                  </svg>
                }
              />
            </>
          )}
        </div>

        <div className="flex items-center gap-2 mb-4">
          <div className="flex-1 border-t border-gray-200 dark:border-[#44475a]" />
          <span className="text-xs text-gray-400 dark:text-[#6272a4]">또는</span>
          <div className="flex-1 border-t border-gray-200 dark:border-[#44475a]" />
        </div>

        {/* 이메일/비밀번호 로그인 */}
        <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
          <div>
            <label htmlFor="email" className="block text-sm font-medium dark:text-[#f8f8f2] mb-1">이메일</label>
            <input
              id="email" type="email" value={email} onChange={e => setEmail(e.target.value)}
              placeholder="이메일" autoComplete="email"
              aria-describedby={errors.email ? "email-error" : undefined}
              aria-invalid={!!errors.email}
              className={inputCls("email")}
            />
            {errors.email && <p id="email-error" className="text-[#ff5555] text-xs mt-1">{errors.email}</p>}
          </div>

          <div>
            <label htmlFor="password" className="block text-sm font-medium dark:text-[#f8f8f2] mb-1">비밀번호</label>
            <input
              id="password" type="password" value={password} onChange={e => setPassword(e.target.value)}
              placeholder="비밀번호" autoComplete="current-password"
              aria-describedby={errors.password ? "password-error" : undefined}
              aria-invalid={!!errors.password}
              className={inputCls("password")}
            />
            {errors.password && <p id="password-error" className="text-[#ff5555] text-xs mt-1">{errors.password}</p>}
          </div>

          {errors.form && (
            <p role="alert" className="text-[#ff5555] text-sm text-center">{errors.form}</p>
          )}

          <button type="submit" disabled={loading}
            className="bg-[#bd93f9] text-[#282a36] py-2 rounded-lg hover:bg-[#ff79c6] disabled:opacity-50 font-semibold transition-colors">
            {loading ? "로그인 중..." : "로그인"}
          </button>

          <div className="text-right">
            <Link href="/forgot-password" className="text-xs text-[#6272a4] hover:text-[#bd93f9]">
              비밀번호를 잊으셨나요?
            </Link>
          </div>
        </form>

        <p className="mt-4 text-center text-sm text-gray-500 dark:text-[#6272a4]">
          계정이 없으신가요?{" "}
          <Link href="/signup" className="text-[#bd93f9] hover:underline">회원가입</Link>
        </p>
      </div>
    </div>
  );
}
