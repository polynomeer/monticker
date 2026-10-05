"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { signup, saveTokens, type SignupConsent } from "@/services/auth";
import { Btn, Checkbox } from "@/components/terminal";
import { AuthField, AuthHeading, AuthShell, FormError, OrDivider } from "@/components/auth/AuthShell";
import { SocialButtons } from "@/components/auth/SocialButtons";
import { cn } from "@/lib/utils";

function passwordStrength(pw: string): { label: string; level: 0 | 1 | 2 | 3 | 4; color: string; text: string } {
  if (pw.length === 0) return { label: "", level: 0, color: "", text: "" };
  let score = 0;
  if (pw.length >= 8) score++;
  if (pw.length >= 12) score++;
  if (/[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  if (score <= 1) return { label: "약함", level: 1, color: "bg-[#ff8a8a]", text: "text-[#ff8a8a]" };
  if (score <= 2) return { label: "보통", level: 2, color: "bg-dracula-orange", text: "text-dracula-orange" };
  if (score <= 3) return { label: "강함", level: 3, color: "bg-dracula-green", text: "text-dracula-green" };
  return { label: "매우 강함", level: 4, color: "bg-dracula-green", text: "text-dracula-green" };
}

type ConsentKey = "terms" | "privacy" | "age" | "marketing";
const REQUIRED: ConsentKey[] = ["terms", "privacy", "age"];

export default function SignupPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [consent, setConsent] = useState<Record<ConsentKey, boolean>>({ terms: false, privacy: false, age: false, marketing: false });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);

  const strength = passwordStrength(password);
  const allAgreed = Object.values(consent).every(Boolean);
  const setAll = (v: boolean) => setConsent({ terms: v, privacy: v, age: v, marketing: v });
  const setOne = (k: ConsentKey) => (v: boolean) => setConsent((c) => ({ ...c, [k]: v }));

  const validate = () => {
    const e: Record<string, string> = {};
    if (!email) e.email = "이메일을 입력해주세요.";
    else if (!/\S+@\S+\.\S+/.test(email)) e.email = "올바른 이메일 형식이 아닙니다.";
    if (!nickname) e.nickname = "닉네임을 입력해주세요.";
    else if (nickname.length < 2) e.nickname = "닉네임은 2자 이상이어야 합니다.";
    if (!password) e.password = "비밀번호를 입력해주세요.";
    else if (password.length < 8) e.password = "비밀번호는 8자 이상이어야 합니다.";
    else if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) e.password = "비밀번호는 영문과 숫자를 포함해야 합니다.";
    if (REQUIRED.some((k) => !consent[k])) e.consent = "필수 항목에 모두 동의해주세요.";
    return e;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errs = validate();
    if (Object.keys(errs).length) { setErrors(errs); return; }
    setErrors({});
    setLoading(true);
    try {
      // 동의 항목은 아직 서버에 기록되지 않는다(SignupRequest에 필드 없음) — 화면에서 필수 동의만 막는다.
      const codes: Record<ConsentKey, SignupConsent> = { terms: "TERMS", privacy: "PRIVACY", age: "AGE_OVER_19", marketing: "MARKETING" };
      const tokens = await signup(email, password, nickname, (Object.keys(codes) as ConsentKey[]).filter((k) => consent[k]).map((k) => codes[k]));
      saveTokens(tokens);
      router.push("/onboarding");
    } catch (err) {
      setErrors({ form: err instanceof Error ? err.message : "회원가입 실패" });
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell title={<>아이디어를 규칙으로,<br />규칙을 <span className="text-dracula-purple">검증된 전략</span>으로</>}>
      <AuthHeading title="회원가입">
        이미 계정이 있나요?{" "}
        <Link href="/login" className="text-dracula-purple hover:text-[#d6bcfb]">로그인</Link>
      </AuthHeading>

      <SocialButtons onError={(m) => setErrors({ form: m })} />
      <OrDivider>또는 이메일로</OrDivider>

      <form onSubmit={handleSubmit} className="flex flex-col gap-5" noValidate>
        <AuthField
          id="email" label="이메일" icon="mail" type="email" autoComplete="email"
          placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)}
          error={errors.email}
        />
        <AuthField
          id="nickname" label="닉네임" icon="user" type="text" autoComplete="username"
          placeholder="전략 마켓에 표시됩니다" value={nickname} onChange={(e) => setNickname(e.target.value)}
          error={errors.nickname}
        />
        <div className="flex flex-col gap-1.5">
          <AuthField
            id="password" label="비밀번호" icon="lock" type="password" autoComplete="new-password"
            placeholder="8자 이상" value={password} onChange={(e) => setPassword(e.target.value)}
            error={errors.password}
          />
          <div id="password-strength" className="flex flex-col gap-1.5" aria-live="polite">
            <div className="flex gap-1" aria-hidden>
              {[1, 2, 3, 4].map((i) => (
                <span key={i} className={cn("h-1 flex-1 rounded-full", i <= strength.level ? strength.color : "bg-tm-line2")} />
              ))}
            </div>
            <span className="text-xs text-tm-muted">
              {strength.label && <>강도: <b className={strength.text}>{strength.label}</b> · </>}8자 이상, 영문·숫자 포함
            </span>
          </div>
        </div>

        <fieldset className="m-0 flex flex-col gap-2.5 rounded-[10px] border-0 bg-tm-panel p-3.5">
          <legend className="sr-only">약관 동의</legend>
          <Checkbox checked={allAgreed} onChange={setAll} label="전체 동의" />
          <span className="h-px bg-tm-line" />
          <ConsentRow checked={consent.terms} onChange={setOne("terms")} label="[필수] 이용약관" href="/terms" />
          <ConsentRow checked={consent.privacy} onChange={setOne("privacy")} label="[필수] 개인정보 수집·이용" href="/privacy" />
          <Checkbox checked={consent.age} onChange={setOne("age")} label="[필수] 만 19세 이상입니다" />
          <Checkbox checked={consent.marketing} onChange={setOne("marketing")} label="[선택] 이벤트·리포트 이메일 수신" />
          {errors.consent && <span className="text-xs text-[#ff8a8a]">{errors.consent}</span>}
        </fieldset>

        {errors.form && <FormError>{errors.form}</FormError>}

        <Btn type="submit" size="xl" full disabled={loading} className="h-12">
          {loading ? "처리 중..." : "가입하기"}
        </Btn>
      </form>
    </AuthShell>
  );
}

function ConsentRow({ checked, onChange, label, href }: { checked: boolean; onChange: (v: boolean) => void; label: string; href: string }) {
  return (
    <span className="flex items-start justify-between gap-3">
      <Checkbox checked={checked} onChange={onChange} label={label} />
      <Link href={href} target="_blank" rel="noopener noreferrer" className="flex-none text-xs text-tm-muted hover:text-dracula-fg">
        보기
      </Link>
    </span>
  );
}
