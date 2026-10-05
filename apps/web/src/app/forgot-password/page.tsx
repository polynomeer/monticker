"use client";
import { useState } from "react";
import Link from "next/link";
import { forgotPassword } from "@/services/auth";
import { Btn, BtnLink } from "@/components/terminal";
import { AuthField } from "@/components/auth/AuthShell";
import { CenteredPage, StatusCard, StatusCardFrame } from "@/components/auth/StatusCard";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [sent, setSent] = useState(false);

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
    <CenteredPage>
      {sent ? (
        <StatusCard
          tone="ok"
          title="메일을 확인해주세요"
          actions={<BtnLink href="/login" kind="ghost" size="lg" full>로그인으로 돌아가기</BtnLink>}
        >
          <p className="m-0">
            <b className="text-dracula-fg">{email}</b>(으)로 등록된 이메일이라면 재설정 링크를 발송했습니다.
          </p>
          <p className="m-0 text-xs text-tm-muted">메일이 오지 않는다면 스팸함도 확인해주세요. 링크는 30분 동안 유효합니다.</p>
        </StatusCard>
      ) : (
        <StatusCardFrame>
          <h1 className="m-0 text-[1.375rem] font-bold">비밀번호 찾기</h1>
          <p className="m-0 leading-relaxed text-tm-soft">가입한 이메일로 재설정 링크를 보내드립니다. 링크는 30분 동안 유효합니다.</p>
          <form onSubmit={handleSubmit} className="flex flex-col gap-[18px]" noValidate>
            <AuthField
              id="email" label="이메일" icon="mail" type="email" autoComplete="email"
              placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)}
              error={error ? <span role="alert">{error}</span> : undefined}
            />
            <Btn type="submit" size="xl" full disabled={loading} className="h-12">
              {loading ? "발송 중..." : "재설정 링크 보내기"}
            </Btn>
          </form>
          <Link href="/login" className="text-center text-13 text-dracula-purple hover:text-[#d6bcfb]">로그인으로 돌아가기</Link>
        </StatusCardFrame>
      )}
    </CenteredPage>
  );
}
