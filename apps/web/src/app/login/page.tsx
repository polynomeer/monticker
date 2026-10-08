"use client";
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { login, saveTokens } from "@/services/auth";
import { Btn, Checkbox, PreviewTag } from "@/components/terminal";
import { AuthField, AuthHeading, AuthShell, FormError, OrDivider } from "@/components/auth/AuthShell";
import { SocialButtons } from "@/components/auth/SocialButtons";
import { loginErrorMessage } from "@/lib/loginError";

/** `?error=oauth2` 등 — 아는 코드만 고정 문구로 보여 준다(쿼리 원문은 찍지 않는다). */
function QueryErrorNotice() {
  const message = loginErrorMessage(useSearchParams().get("error"));
  return message ? <FormError>{message}</FormError> : null;
}

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  // 리다이렉트로 받은 오류는 사용자가 다시 시도하면 지운다(새 결과와 섞이지 않게)
  const [queryErrorDismissed, setQueryErrorDismissed] = useState(false);

  const validate = () => {
    const e: Record<string, string> = {};
    if (!email) e.email = "이메일을 입력해주세요.";
    if (!password) e.password = "비밀번호를 입력해주세요.";
    return e;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setQueryErrorDismissed(true);
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

  return (
    <AuthShell
      title={<>가격이 <span className="text-dracula-purple">왜</span> 움직였는지<br />차트 위에서 바로 봅니다</>}
    >
      <AuthHeading title="로그인">
        계정이 없나요?{" "}
        <Link href="/signup" className="text-dracula-purple hover:text-[#d6bcfb]">회원가입</Link>
      </AuthHeading>

      {!queryErrorDismissed && (
        // useSearchParams는 정적 렌더에서 Suspense 경계가 필요하다 — 폼 전체가 아니라 이 알림만 감싼다
        <Suspense fallback={null}>
          <QueryErrorNotice />
        </Suspense>
      )}
      <SocialButtons onError={(m) => { setQueryErrorDismissed(true); setErrors({ form: m }); }} />
      <OrDivider>또는</OrDivider>

      <form onSubmit={handleSubmit} className="flex flex-col gap-5" noValidate>
        <AuthField
          id="email" label="이메일" icon="mail" type="email" autoComplete="email"
          placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)}
          error={errors.email}
        />
        <AuthField
          id="password" label="비밀번호" icon="lock" type="password" autoComplete="current-password"
          placeholder="비밀번호" value={password} onChange={(e) => setPassword(e.target.value)}
          error={errors.password}
        />

        <div className="flex items-center justify-between gap-3">
          {/* 로그인 유지 기간 선택은 아직 없다 — refresh 토큰 쿠키 수명이 고정. 시안 요소만 보여준다. */}
          <span className="flex items-center gap-2">
            <Checkbox checked disabled label="로그인 상태 유지" />
            <PreviewTag />
          </span>
          <Link href="/forgot-password" className="text-13 text-dracula-purple hover:text-[#d6bcfb]">비밀번호 찾기</Link>
        </div>

        {errors.form && <FormError>{errors.form}</FormError>}

        <Btn type="submit" size="xl" full disabled={loading} className="h-12">
          {loading ? "로그인 중..." : "로그인"}
        </Btn>
      </form>
    </AuthShell>
  );
}
