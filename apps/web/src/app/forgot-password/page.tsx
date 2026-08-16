"use client";
import { useState } from "react";
import Link from "next/link";
import { CheckCircle } from "@phosphor-icons/react";
import { forgotPassword } from "@/services/auth";
import { Card } from "@/components/ui/Card";

export default function ForgotPasswordPage() {
  const [email,   setEmail]   = useState("");
  const [error,   setError]   = useState("");
  const [loading, setLoading] = useState(false);
  const [sent,    setSent]    = useState(false);

  const inputCls = `border rounded-lg px-4 py-2 w-full transition-all duration-150 focus:outline-none focus:ring-2 hover:border-gray-400 dark:hover:border-dracula-comment dark:bg-dracula-line dark:text-dracula-fg dark:placeholder-dracula-comment ${
    error
      ? "border-dracula-red focus:ring-dracula-red/50"
      : "border-gray-300 dark:border-dracula-line focus:ring-dracula-purple/50 focus:border-dracula-purple"
  }`;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email) { setError("이메일을 입력해주세요."); return; }
    setError("");
    setLoading(true);
    try {
      await forgotPassword(email);
      setSent(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "요청에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-dracula-bg bg-mesh-light dark:bg-mesh-dark px-4">
      <Card className="p-8" outerClassName="w-full max-w-sm animate-fade-up">
        <h1 className="text-2xl font-bold mb-2 text-center dark:text-dracula-fg">비밀번호 찾기</h1>
        <p className="text-sm text-gray-500 dark:text-dracula-comment text-center mb-6">
          가입한 이메일로 재설정 링크를 보내드립니다.
        </p>

        {sent ? (
          <div className="text-center py-4 animate-fade-up">
            <div className="flex justify-center mb-3 text-dracula-green"><CheckCircle size={40} weight="duotone" aria-hidden /></div>
            <p className="text-sm text-gray-700 dark:text-dracula-fg">
              <span className="font-semibold">{email}</span>(으)로<br />
              등록된 이메일이라면 재설정 링크를 발송했습니다.
            </p>
            <p className="text-xs text-gray-500 dark:text-dracula-comment mt-2">
              메일이 오지 않는다면 스팸함도 확인해주세요. 링크는 30분간 유효합니다.
            </p>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
            <div>
              <label htmlFor="email" className="block text-sm font-medium dark:text-dracula-fg mb-1">이메일</label>
              <input
                id="email" type="email" value={email} onChange={e => setEmail(e.target.value)}
                placeholder="you@example.com" autoComplete="email"
                aria-describedby={error ? "email-error" : undefined}
                aria-invalid={!!error}
                className={inputCls}
              />
              {error && <p id="email-error" role="alert" className="text-dracula-red text-xs mt-1">{error}</p>}
            </div>

            <button type="submit" disabled={loading}
              className="bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg py-2 rounded-lg hover:opacity-90 active:scale-[0.98] disabled:opacity-50 disabled:active:scale-100 font-semibold transition-all duration-150">
              {loading ? "발송 중..." : "재설정 링크 받기"}
            </button>
          </form>
        )}

        <p className="mt-6 text-center text-sm text-gray-500 dark:text-dracula-comment">
          <Link href="/login" className="text-blue-600 dark:text-dracula-purple hover:underline">로그인으로 돌아가기</Link>
        </p>
      </Card>
    </div>
  );
}
