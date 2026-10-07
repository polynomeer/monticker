"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { CenteredPage, StatusCardFrame } from "@/components/auth/StatusCard";
import { Btn, Checkbox, Notice } from "@/components/terminal";
import { useAuth } from "@/hooks/useAuth";
import { useAgreeConsents, useConsentStatus } from "@/hooks/useConsents";
import { safeNext, type ConsentType } from "@/services/consent";

const LABEL: Partial<Record<ConsentType, { text: string; href?: string }>> = {
  TERMS: { text: "[필수] 이용약관", href: "/terms" },
  PRIVACY: { text: "[필수] 개인정보 수집·이용", href: "/privacy" },
  AGE_OVER_19: { text: "[필수] 만 19세 이상입니다" },
};

/**
 * ADR-068 — 가입 필수 동의를 (다시) 받는 화면. 소셜 가입은 가입 폼을 거치지 않고, 약관이 개정되면 기존 회원도 다시 동의해야 한다.
 * 앱 화면(TerminalPage)의 게이트가 이리로 보낸다.
 */
function ConsentPrompt() {
  const router = useRouter();
  const params = useSearchParams();
  const next = safeNext(params.get("next"));
  const { isLoggedIn } = useAuth();
  const [checked, setChecked] = useState(false);
  useEffect(() => setChecked(true), []);
  const { data, isLoading } = useConsentStatus(isLoggedIn);
  const agree = useAgreeConsents();
  const [given, setGiven] = useState<Partial<Record<ConsentType, boolean>>>({});
  const [marketing, setMarketing] = useState(false);

  useEffect(() => {
    if (checked && !isLoggedIn && !localStorage.getItem("accessToken")) router.replace("/login");
  }, [checked, isLoggedIn, router]);
  useEffect(() => {
    if (data && data.missingRequired.length === 0 && !agree.isPending) router.replace(next);
  }, [data, next, router, agree.isPending]);

  const missing = data?.missingRequired ?? [];
  const marketingAgreed = data?.states.find((s) => s.type === "MARKETING")?.agreed ?? false;
  const revised = (data?.states ?? []).some((s) => missing.includes(s.type) && s.documentVersion != null);
  const allGiven = missing.length > 0 && missing.every((t) => given[t]);

  const submit = () =>
    agree.mutate([...missing, ...(marketing && !marketingAgreed ? (["MARKETING"] as ConsentType[]) : [])], {
      onSuccess: () => router.replace(next),
    });

  return (
    <CenteredPage>
      <StatusCardFrame>
        <div className="flex flex-col gap-1.5">
          <h1 className="m-0 text-[1.375rem] font-bold">{revised ? "약관이 바뀌었습니다" : "서비스 이용 동의"}</h1>
          <p className="m-0 leading-relaxed text-tm-soft">
            {revised ? "바뀐 내용을 확인하고 다시 동의해주세요." : "계속하려면 아래 필수 항목에 동의해주세요."}
          </p>
        </div>

        {isLoading || !data ? (
          <div className="h-32 animate-pulse rounded-[10px] bg-tm-inner" aria-busy="true" />
        ) : (
          <fieldset className="m-0 flex flex-col gap-2.5 rounded-[10px] border-0 bg-tm-inner p-3.5">
            <legend className="sr-only">동의 항목</legend>
            <Checkbox
              checked={allGiven && (marketingAgreed || marketing)}
              onChange={(v) => { setGiven(Object.fromEntries(missing.map((t) => [t, v]))); if (!marketingAgreed) setMarketing(v); }}
              label="전체 동의"
            />
            <span className="h-px bg-tm-line" />
            {missing.map((t) => (
              <div key={t} className="flex items-center justify-between gap-3">
                <Checkbox checked={!!given[t]} onChange={(v) => setGiven((g) => ({ ...g, [t]: v }))} label={LABEL[t]?.text ?? t} />
                {LABEL[t]?.href && (
                  <Link href={LABEL[t]!.href!} target="_blank" className="text-xs text-dracula-purple hover:underline">보기</Link>
                )}
              </div>
            ))}
            {!marketingAgreed && <Checkbox checked={marketing} onChange={setMarketing} label="[선택] 이벤트·리포트 이메일 수신" />}
          </fieldset>
        )}

        {agree.isError && <Notice tone="danger">{agree.error instanceof Error ? agree.error.message : "동의를 저장하지 못했습니다."}</Notice>}

        <Btn kind="primary" size="xl" full disabled={!allGiven || agree.isPending} onClick={submit}>
          {agree.isPending ? "저장 중..." : "동의하고 계속"}
        </Btn>
      </StatusCardFrame>
    </CenteredPage>
  );
}

export default function ConsentPage() {
  return (
    <Suspense fallback={null}>
      <ConsentPrompt />
    </Suspense>
  );
}
