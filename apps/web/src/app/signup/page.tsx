"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { signup, saveTokens } from "@/services/auth";
import Link from "next/link";

function passwordStrength(pw: string): { label: string; color: string; width: string } {
  if (pw.length === 0) return { label: "", color: "", width: "0%" };
  let score = 0;
  if (pw.length >= 8)  score++;
  if (pw.length >= 12) score++;
  if (/[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  if (score <= 1) return { label: "약함",   color: "bg-[#ff5555]", width: "25%" };
  if (score <= 2) return { label: "보통",   color: "bg-[#ffb86c]", width: "50%" };
  if (score <= 3) return { label: "강함",   color: "bg-[#50fa7b]", width: "75%" };
  return            { label: "매우 강함", color: "bg-[#50fa7b]", width: "100%" };
}

export default function SignupPage() {
  const router = useRouter();
  const [email,    setEmail]    = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [errors,   setErrors]   = useState<Record<string, string>>({});
  const [loading,  setLoading]  = useState(false);

  const strength = passwordStrength(password);

  const validate = () => {
    const e: Record<string, string> = {};
    if (!email)                  e.email    = "이메일을 입력해주세요.";
    else if (!/\S+@\S+\.\S+/.test(email)) e.email = "올바른 이메일 형식이 아닙니다.";
    if (!nickname)               e.nickname = "닉네임을 입력해주세요.";
    else if (nickname.length < 2) e.nickname = "닉네임은 2자 이상이어야 합니다.";
    if (!password)               e.password = "비밀번호를 입력해주세요.";
    else if (password.length < 8) e.password = "비밀번호는 8자 이상이어야 합니다.";
    return e;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errs = validate();
    if (Object.keys(errs).length) { setErrors(errs); return; }
    setErrors({});
    setLoading(true);
    try {
      const tokens = await signup(email, password, nickname);
      saveTokens(tokens);
      router.push("/onboarding");
    } catch (err) {
      setErrors({ form: err instanceof Error ? err.message : "회원가입 실패" });
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
        <h1 className="text-2xl font-bold mb-6 text-center dark:text-[#f8f8f2]">회원가입</h1>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>

          {/* 이메일 */}
          <div>
            <label htmlFor="email" className="block text-sm font-medium dark:text-[#f8f8f2] mb-1">이메일</label>
            <input
              id="email" type="email" value={email} onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com" autoComplete="email"
              aria-describedby={errors.email ? "email-error" : undefined}
              aria-invalid={!!errors.email}
              className={inputCls("email")}
            />
            {errors.email && <p id="email-error" className="text-[#ff5555] text-xs mt-1">{errors.email}</p>}
          </div>

          {/* 닉네임 */}
          <div>
            <label htmlFor="nickname" className="block text-sm font-medium dark:text-[#f8f8f2] mb-1">닉네임</label>
            <input
              id="nickname" type="text" value={nickname} onChange={e => setNickname(e.target.value)}
              placeholder="2~30자" autoComplete="username"
              aria-describedby={errors.nickname ? "nickname-error" : undefined}
              aria-invalid={!!errors.nickname}
              className={inputCls("nickname")}
            />
            {errors.nickname && <p id="nickname-error" className="text-[#ff5555] text-xs mt-1">{errors.nickname}</p>}
          </div>

          {/* 비밀번호 + 강도 */}
          <div>
            <label htmlFor="password" className="block text-sm font-medium dark:text-[#f8f8f2] mb-1">비밀번호</label>
            <input
              id="password" type="password" value={password} onChange={e => setPassword(e.target.value)}
              placeholder="8자 이상" autoComplete="new-password"
              aria-describedby="password-strength password-error"
              aria-invalid={!!errors.password}
              className={inputCls("password")}
            />
            {password && (
              <div id="password-strength" className="mt-1.5 space-y-0.5" aria-label={`비밀번호 강도: ${strength.label}`}>
                <div className="h-1 w-full rounded bg-[#44475a]">
                  <div className={`h-1 rounded transition-all duration-300 ${strength.color}`} style={{ width: strength.width }} />
                </div>
                <p className="text-xs text-[#6272a4]">강도: <span className="font-medium">{strength.label}</span></p>
              </div>
            )}
            {errors.password && <p id="password-error" className="text-[#ff5555] text-xs mt-1">{errors.password}</p>}
          </div>

          {errors.form && (
            <p role="alert" className="text-[#ff5555] text-sm text-center">{errors.form}</p>
          )}

          <button type="submit" disabled={loading}
            className="bg-[#bd93f9] text-[#282a36] py-2 rounded-lg hover:bg-[#ff79c6] disabled:opacity-50 font-semibold transition-colors">
            {loading ? "처리 중..." : "회원가입"}
          </button>
        </form>
        <p className="mt-4 text-center text-sm text-gray-500 dark:text-[#6272a4]">
          이미 계정이 있으신가요?{" "}
          <Link href="/login" className="text-[#bd93f9] hover:underline">로그인</Link>
        </p>
      </div>
    </div>
  );
}
