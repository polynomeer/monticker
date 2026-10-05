"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { getAccessToken } from "@/services/auth";
import { useBrokerageAccount, useConnectBrokerage } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { Btn, BtnLink, Checkbox, Icon, Notice, Panel, PanelRow, PreviewTag, TerminalPage, TextField, type IconName } from "@/components/terminal";
import { LoginRequired, maskAccount } from "@/components/brokerage/shared";
import { BROKERAGE_PROVIDER_LABELS, brokerageProviderLabel } from "@/lib/brokerageProvider";
import { cn } from "@/lib/utils";
import type { BrokerageProviderId } from "@monticker/types";

const PROVIDER_META: Record<BrokerageProviderId, {
  sub: string;
  keyLabel: string;
  secretLabel: string;
  keyPlaceholder: string;
  secretPlaceholder: string;
  accountPlaceholder: string;
  portalName: string;
  portalUrl: string;
}> = {
  KIS: {
    sub: "KIS Developers Open API",
    keyLabel: "App Key",
    secretLabel: "App Secret",
    keyPlaceholder: "한국투자증권에서 발급받은 App Key",
    secretPlaceholder: "한국투자증권에서 발급받은 App Secret",
    accountPlaceholder: "숫자만 입력 (예: 1234567801)",
    portalName: "한국투자증권 Open API 포털",
    portalUrl: "https://apiportal.koreainvestment.com",
  },
  TOSS: {
    sub: "Open API",
    keyLabel: "Client ID",
    secretLabel: "Client Secret",
    keyPlaceholder: "토스증권에서 발급받은 Client ID",
    secretPlaceholder: "토스증권에서 발급받은 Client Secret",
    accountPlaceholder: "숫자만 입력 (예: 12345678901)",
    portalName: "토스증권 Open API 개발자 센터",
    portalUrl: "https://developers.tossinvest.com",
  },
};

const PROVIDERS = Object.keys(PROVIDER_META) as BrokerageProviderId[];
const STEPS = ["증권사 선택", "API 키 등록", "권한 확인", "완료"];

const CONSENTS = [
  "본인 명의 계좌의 API 키이며, 주문 권한을 monticker에 위임하는 것에 동의합니다",
  "monticker는 자금을 보관하지 않고 증권사 API 호출만 대행함을 이해했습니다",
  "실제 주문의 체결 결과·손실은 본인에게 귀속됨을 이해했습니다",
];

const SECURITY: { icon: IconName; title: string; desc: string; preview?: boolean }[] = [
  { icon: "lock", title: "암호화 저장", desc: "API 키는 암호화되어 저장되고 화면·로그에 다시 표시되지 않습니다." },
  { icon: "shield", title: "필요한 권한만", desc: "잔고 조회와 주문 외의 권한은 사용하지 않습니다. monticker를 통해서는 출금·이체를 할 수 없습니다." },
  { icon: "key", title: "비밀번호·인증서 미수집", desc: "계좌 비밀번호나 공동인증서는 요구하지 않습니다." },
  { icon: "trash", title: "언제든 해지", desc: "연동 해지(등록된 키 즉시 파기) 기능은 준비 중입니다. 다른 계좌로 다시 연동하면 기존 계좌는 비활성화됩니다.", preview: true },
];

function Stepper({ current }: { current: number }) {
  return (
    <ol className="m-0 flex list-none flex-wrap gap-2.5 p-0" aria-label="연동 단계">
      {STEPS.map((s, i) => {
        const done = i < current;
        const on = i === current;
        return (
          <li key={s} className="flex flex-[1_1_140px] items-center gap-2.5" aria-current={on ? "step" : undefined}>
            <span
              className={cn(
                "num grid h-7 w-7 flex-none place-items-center rounded-full text-xs font-bold",
                done ? "bg-dracula-green text-tm-page" : on ? "bg-dracula-purple text-tm-page" : "border-[1.5px] border-tm-line2 text-tm-muted",
              )}
            >
              {done ? <Icon name="check" size={14} strokeWidth={3} /> : i + 1}
            </span>
            <span className={cn("text-13", on ? "font-bold" : "text-tm-muted")}>{s}</span>
            {i < STEPS.length - 1 && <span className="h-px flex-1 bg-tm-line2" aria-hidden />}
          </li>
        );
      })}
    </ol>
  );
}

export default function BrokerageConnectPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [provider, setProvider] = useState<BrokerageProviderId>("KIS");
  const [appKey, setAppKey] = useState("");
  const [appSecret, setAppSecret] = useState("");
  const [accountNumber, setAccountNumber] = useState("");
  const [consents, setConsents] = useState<boolean[]>(CONSENTS.map(() => false));
  const router = useRouter();
  const { toast } = useToast();
  const { data: account } = useBrokerageAccount();
  const connect = useConnectBrokerage();

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  // ADR-027 — 재인증이 필요한 계좌면 기존 증권사/계좌번호를 채워 폼을 그대로 보여준다.
  useEffect(() => {
    if (account && !account.tokenValid) {
      setProvider(account.provider as BrokerageProviderId);
      setAccountNumber(account.accountNumber);
    }
  }, [account]);

  const meta = PROVIDER_META[provider];
  const fieldsFilled = appKey.trim().length > 0 && appSecret.trim().length > 0 && accountNumber.trim().length > 0;
  const allConsented = consents.every(Boolean);
  const isValid = fieldsFilled && allConsented;
  const needsReconnect = !!account && !account.tokenValid;
  // 진행 표시 — 증권사는 기본 선택돼 있으니 1단계는 항상 완료. 서버가 토큰을 발급해 보는 동안이 "권한 확인".
  const step = connect.isPending ? 2 : 1;

  const handleSubmit = async () => {
    // 비밀값은 상태에만 두고 로그·토스트에 절대 싣지 않는다.
    try {
      await connect.mutateAsync({ provider, appKey: appKey.trim(), appSecret: appSecret.trim(), accountNumber: accountNumber.trim() });
      setAppKey("");
      setAppSecret("");
      toast({ type: "success", title: "연동 완료", message: "증권사 계좌가 연동되었습니다." });
      router.push("/brokerage");
    } catch (e) {
      toast({ type: "error", title: "연동 실패", message: (e as Error).message });
    }
  };

  const topStats = [
    { label: "방식", value: "BYOK (본인 API 키)" },
    { label: "지원", value: "한국투자증권 · 토스증권" },
    { label: "자금 보관", value: "없음", tone: "text-dracula-green" },
  ];

  if (!isLoggedIn) return <LoginRequired title="증권사 계좌 연동" message="실전투자를 연동하려면 로그인이 필요합니다." />;

  if (account && account.tokenValid) return (
    <TerminalPage title="증권사 계좌 연동" crumb="실전투자 · 4 / 4 단계" stats={topStats} account={{ kind: "live" }}>
      <div className="mx-auto w-full max-w-[520px] pt-10">
        <Panel tabs={["연동 완료"]} actions={[]} closable={false} bodyClassName="items-center gap-3 p-8 text-center">
          <span className="grid h-10 w-10 place-items-center rounded-full bg-[#22392c] text-dracula-green"><Icon name="check" size={20} strokeWidth={2.4} /></span>
          <p className="m-0 text-15 font-semibold">이미 계좌가 연동되어 있습니다</p>
          <p className="num m-0 text-xs text-tm-muted">
            {brokerageProviderLabel(account.provider)} · 계좌번호 {maskAccount(account.accountNumber)}
          </p>
          <BtnLink href="/brokerage">대시보드로 이동</BtnLink>
        </Panel>
      </div>
    </TerminalPage>
  );

  return (
    <TerminalPage title="증권사 계좌 연동" crumb={`실전투자 · ${step + 1} / 4 단계`} stats={topStats} account={{ kind: "live" }}>
      <PanelRow>
        <Panel tabs={["증권사 계좌 연동"]} actions={[]} closable={false} className="flex-[999_1_600px]" bodyClassName="gap-4 p-5">
          <Stepper current={step} />

          {needsReconnect && (
            <Notice tone="danger">
              <b className="text-[#ff8a8a]">재인증이 필요합니다</b>
              <br />
              증권사 인증이 만료되었거나 앱키/시크릿이 변경되었을 수 있습니다. 아래에서 다시 발급받은 정보로 재연동해주세요.
            </Notice>
          )}

          <div className="flex flex-col gap-2">
            <span className="text-xs text-tm-muted">증권사</span>
            <div className="flex flex-wrap gap-2">
              {PROVIDERS.map(p => {
                const on = provider === p;
                return (
                  <button
                    key={p}
                    type="button"
                    aria-pressed={on}
                    onClick={() => setProvider(p)}
                    className={cn(
                      "flex flex-[1_1_200px] items-center gap-3 rounded-[10px] p-3.5 text-left text-dracula-fg",
                      on ? "border-[1.5px] border-dracula-purple bg-[#3a2f52]" : "border border-tm-line2 bg-tm-inner hover:bg-tm-raised",
                    )}
                  >
                    <span className="grid h-9 w-9 flex-none place-items-center rounded-[10px] bg-tm-raised"><Icon name="bank" size={18} /></span>
                    <span className="flex flex-col gap-0.5">
                      <span className="font-bold">{BROKERAGE_PROVIDER_LABELS[p]}</span>
                      <span className="text-xs text-tm-muted">{PROVIDER_META[p].sub}</span>
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          <TextField
            key={`${provider}-key`}
            label={meta.keyLabel}
            icon="key"
            type="password"
            placeholder={meta.keyPlaceholder}
            value={appKey}
            onChange={e => setAppKey(e.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
          <TextField
            key={`${provider}-secret`}
            label={meta.secretLabel}
            icon="lock"
            type="password"
            placeholder={meta.secretPlaceholder}
            value={appSecret}
            onChange={e => setAppSecret(e.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
          <TextField
            label="계좌번호"
            inputMode="numeric"
            placeholder={meta.accountPlaceholder}
            value={accountNumber}
            onChange={e => setAccountNumber(e.target.value.replace(/[^0-9]/g, ""))}
            autoComplete="off"
            hint={
              <>
                본인 명의 위탁계좌만 연동할 수 있습니다. 키 발급 방법은{" "}
                <a href={meta.portalUrl} target="_blank" rel="noopener noreferrer" className="underline hover:text-dracula-fg">{meta.portalName}</a>
                의 안내를 참고하세요.
              </>
            }
          />

          <div className="flex flex-col gap-2.5 rounded-[10px] bg-tm-inner p-3.5">
            {CONSENTS.map((c, i) => (
              <Checkbox
                key={c}
                label={c}
                checked={consents[i]}
                onChange={v => setConsents(cs => cs.map((x, j) => (j === i ? v : x)))}
              />
            ))}
          </div>

          <div className="flex flex-wrap justify-end gap-2">
            <BtnLink href="/brokerage" kind="ghost" size="lg">이전</BtnLink>
            <Btn size="lg" onClick={handleSubmit} disabled={!isValid || connect.isPending}>
              {connect.isPending
                ? "연결 확인 중..."
                : needsReconnect
                  ? `${BROKERAGE_PROVIDER_LABELS[provider]} 연결 테스트 후 재연동`
                  : "연결 테스트 후 다음"}
            </Btn>
          </div>
          {fieldsFilled && !allConsented && (
            <span className="text-right text-xs text-tm-muted">위 동의 항목을 모두 확인해야 연동할 수 있습니다.</span>
          )}
        </Panel>

        <Panel tabs={["안전하게 연결됩니다"]} actions={[]} closable={false} className="flex-[1_1_340px] self-start">
          <ul className="m-0 list-none p-0">
            {SECURITY.map(s => (
              <li key={s.title} className="flex gap-3 border-b border-tm-line py-3">
                <span className="pt-px text-dracula-green"><Icon name={s.icon} size={18} /></span>
                <div className="flex flex-col gap-[3px]">
                  <span className="flex items-center gap-1.5 font-semibold">{s.title}{s.preview && <PreviewTag />}</span>
                  <span className="text-xs leading-relaxed text-tm-muted">{s.desc}</span>
                </div>
              </li>
            ))}
          </ul>
          <span className="text-xs">
            <Link href="/privacy" className="text-dracula-purple hover:underline">개인정보 처리방침</Link>
            {" · "}
            <Link href="/terms" className="text-dracula-purple hover:underline">이용약관 →</Link>
          </span>
          <span className="text-xs text-tm-muted">실제 자금이 이동하는 기능입니다. 리스크 한도 내에서만 주문이 체결됩니다.</span>
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
