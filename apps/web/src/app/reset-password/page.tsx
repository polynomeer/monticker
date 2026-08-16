"use client";
import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { CheckCircle, WarningCircle } from "@phosphor-icons/react";
import { resetPassword } from "@/services/auth";
import { Card } from "@/components/ui/Card";

export default function ResetPasswordPage() {
  const router = useRouter();
  const token  = useSearchParams().get("token");

  const [password,        setPassword]        = useState("");
  const [confirmPassword, setConfirmPassword]  = useState("");
  const [errors,          setErrors]           = useState<Record<string, string>>({});
  const [loading,         setLoading]          = useState(false);
  const [done,            setDone]             = useState(false);

  const inputCls = (field: string) =>
    `border rounded-lg px-4 py-2 w-full transition-all duration-150 focus:outline-none focus:ring-2 hover:border-gray-400 dark:hover:border-dracula-comment dark:bg-dracula-line dark:text-dracula-fg dark:placeholder-dracula-comment ${
      errors[field]
        ? "border-dracula-red focus:ring-dracula-red/50"
        : "border-gray-300 dark:border-dracula-line focus:ring-dracula-purple/50 focus:border-dracula-purple"
    }`;

  const validate = () => {
    const e: Record<string, string> = {};
    if (password.length < 8) e.password = "비밀번호는 8자 이상이어야 합니다.";
    if (confirmPassword !== password) e.confirmPassword = "비밀번호가 일치하지 않습니다.";
    return e;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errs = validate();
    if (Object.keys(errs).length) { setErrors(errs); return; }
    setErrors({});
    setLoading(true);
    try {
      await resetPassword(token!, password);
      setDone(true);
      setTimeout(() => router.push("/login"), 2000);
    } catch (err) {
      setErrors({ form: err instanceof Error ? err.message : "비밀번호 재설정에 실패했습니다." });
    } finally {
      setLoading(false);
    }
  };

  if (!token) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-dracula-bg bg-mesh-light dark:bg-mesh-dark px-4">
        <Card className="p-8 text-center" outerClassName="w-full max-w-sm animate-fade-up">
          <div className="flex justify-center mb-3 text-dracula-red"><WarningCircle size={40} weight="duotone" aria-hidden /></div>
          <h1 className="text-xl font-bold mb-2 dark:text-dracula-fg">유효하지 않은 링크입니다</h1>
          <p className="text-sm text-gray-500 dark:text-dracula-comment mb-6">
            비밀번호 재설정 링크가 올바르지 않습니다. 다시 요청해주세요.
          </p>
          <Link href="/forgot-password"
            className="inline-block bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg px-5 py-2 rounded-lg font-semibold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150">
            비밀번호 찾기로 이동
          </Link>
        </Card>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-dracula-bg bg-mesh-light dark:bg-mesh-dark px-4">
      <Card className="p-8" outerClassName="w-full max-w-sm animate-fade-up">
        <h1 className="text-2xl font-bold mb-6 text-center dark:text-dracula-fg">새 비밀번호 설정</h1>

        {done ? (
          <div className="text-center py-4 animate-fade-up">
            <div className="flex justify-center mb-3 text-dracula-green"><CheckCircle size={40} weight="duotone" aria-hidden /></div>
            <p className="text-sm text-gray-700 dark:text-dracula-fg">비밀번호가 변경되었습니다.</p>
            <p className="text-xs text-gray-500 dark:text-dracula-comment mt-1">잠시 후 로그인 페이지로 이동합니다...</p>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
            <div>
              <label htmlFor="password" className="block text-sm font-medium dark:text-dracula-fg mb-1">새 비밀번호</label>
              <input
                id="password" type="password" value={password} onChange={e => setPassword(e.target.value)}
                placeholder="8자 이상" autoComplete="new-password"
                aria-describedby={errors.password ? "password-error" : undefined}
                aria-invalid={!!errors.password}
                className={inputCls("password")}
              />
              {errors.password && <p id="password-error" className="text-dracula-red text-xs mt-1">{errors.password}</p>}
            </div>

            <div>
              <label htmlFor="confirmPassword" className="block text-sm font-medium dark:text-dracula-fg mb-1">새 비밀번호 확인</label>
              <input
                id="confirmPassword" type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)}
                placeholder="비밀번호 재입력" autoComplete="new-password"
                aria-describedby={errors.confirmPassword ? "confirm-password-error" : undefined}
                aria-invalid={!!errors.confirmPassword}
                className={inputCls("confirmPassword")}
              />
              {errors.confirmPassword && <p id="confirm-password-error" className="text-dracula-red text-xs mt-1">{errors.confirmPassword}</p>}
            </div>

            {errors.form && (
              <p role="alert" className="text-dracula-red text-sm text-center">{errors.form}</p>
            )}

            <button type="submit" disabled={loading}
              className="bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg py-2 rounded-lg hover:opacity-90 active:scale-[0.98] disabled:opacity-50 disabled:active:scale-100 font-semibold transition-all duration-150">
              {loading ? "변경 중..." : "비밀번호 변경"}
            </button>
          </form>
        )}

        {!done && (
          <p className="mt-6 text-center text-sm text-gray-500 dark:text-dracula-comment">
            <Link href="/login" className="text-blue-600 dark:text-dracula-purple hover:underline">로그인으로 돌아가기</Link>
          </p>
        )}
      </Card>
    </div>
  );
}
