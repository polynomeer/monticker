"use client";
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { resetPassword } from "@/services/auth";
import { Btn, BtnLink } from "@/components/terminal";
import { AuthField, FormError } from "@/components/auth/AuthShell";
import { CenteredPage, StatusCard, StatusCardFrame } from "@/components/auth/StatusCard";

function ResetPasswordContent() {
  const router = useRouter();
  const token = useSearchParams().get("token");

  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  const validate = () => {
    const e: Record<string, string> = {};
    if (password.length < 8) e.password = "비밀번호는 8자 이상이어야 합니다.";
    else if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) e.password = "비밀번호는 영문과 숫자를 포함해야 합니다.";
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
      <StatusCard
        tone="error"
        title="유효하지 않은 링크입니다"
        actions={<BtnLink href="/forgot-password" size="lg" full>다시 요청하기</BtnLink>}
      >
        <p className="m-0">비밀번호 재설정 링크가 올바르지 않거나 만료되었습니다. 다시 요청해주세요.</p>
      </StatusCard>
    );
  }

  if (done) {
    return (
      <StatusCard
        tone="ok"
        title="비밀번호가 변경되었습니다"
        actions={<BtnLink href="/login" kind="ghost" size="lg" full>지금 로그인</BtnLink>}
      >
        <p className="m-0">잠시 후 로그인 페이지로 이동합니다…</p>
      </StatusCard>
    );
  }

  const matches = confirmPassword.length > 0 && confirmPassword === password;

  return (
    <StatusCardFrame>
      <h1 className="m-0 text-[1.375rem] font-bold">새 비밀번호 설정</h1>
      <form onSubmit={handleSubmit} className="flex flex-col gap-[18px]" noValidate>
        <AuthField
          id="password" label="새 비밀번호" icon="lock" type="password" autoComplete="new-password"
          placeholder="8자 이상, 영문·숫자 포함" value={password} onChange={(e) => setPassword(e.target.value)}
          error={errors.password}
        />
        <AuthField
          id="confirmPassword" label="새 비밀번호 확인" icon="lock" type="password" autoComplete="new-password"
          placeholder="한 번 더 입력" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)}
          error={errors.confirmPassword}
          hint={matches ? "두 비밀번호가 일치합니다." : undefined}
        />
        {errors.form && <FormError>{errors.form}</FormError>}
        <Btn type="submit" size="xl" full disabled={loading} className="h-12">
          {loading ? "변경 중..." : "비밀번호 변경"}
        </Btn>
      </form>
      <Link href="/login" className="text-center text-13 text-dracula-purple hover:text-[#d6bcfb]">로그인으로 돌아가기</Link>
    </StatusCardFrame>
  );
}

export default function ResetPasswordPage() {
  return (
    <CenteredPage>
      <Suspense fallback={null}>
        <ResetPasswordContent />
      </Suspense>
    </CenteredPage>
  );
}
