"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { login, saveTokens } from "@/services/auth";
import Link from "next/link";

export default function LoginPage() {
  const router = useRouter();
  const [email,    setEmail]    = useState("");
  const [password, setPassword] = useState("");
  const [errors,   setErrors]   = useState<Record<string, string>>({});
  const [loading,  setLoading]  = useState(false);

  const validate = () => {
    const e: Record<string, string> = {};
    if (!email)   e.email    = "이메일을 입력해주세요.";
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
