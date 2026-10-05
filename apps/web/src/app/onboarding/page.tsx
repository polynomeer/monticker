"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Btn, Icon, PreviewTag, type IconName } from "@/components/terminal";
import { BrandLink } from "@/components/auth/AuthShell";
import { cn } from "@/lib/utils";

const TOTAL = 4;

const SECTORS = ["반도체", "2차전지", "인터넷·플랫폼", "바이오", "금융", "자동차", "배당주", "ETF", "조선·방산"];

const STYLES: { key: string; icon: IconName; title: string; desc: string }[] = [
  { key: "observe", icon: "eye", title: "관찰 위주", desc: "차트와 이벤트를 먼저 익히고 싶어요" },
  { key: "event", icon: "trend", title: "단기 이벤트 매매", desc: "급등·급락 신호에 빠르게 반응하고 싶어요" },
  { key: "quant", icon: "flask", title: "규칙 기반 퀀트", desc: "전략을 만들고 검증하고 싶어요" },
];

/** 모의투자 시작 자금 — 현재 서버는 1,000만원 고정(PaperAccount.INITIAL_BALANCE). 나머지는 시안 요소. */
const CAPITAL = [
  { key: "10m", label: "1,000만원", available: true },
  { key: "30m", label: "3,000만원", available: false },
  { key: "100m", label: "1억원", available: false },
];

const FEATURES: { icon: IconName; title: string; desc: string; note?: string }[] = [
  { icon: "star", title: "관심 종목", desc: "원하는 종목을 관심종목에 담아 시세와 이벤트를 한눈에 확인하세요." },
  { icon: "bell", title: "가격 알림", desc: "목표가에 도달하면 즉시 알림을 받을 수 있습니다." },
  { icon: "flask", title: "퀀트랩", desc: "나만의 매매 조건을 설정하고 과거 데이터로 백테스트 해보세요." },
  { icon: "shield", title: "조건부 주문", desc: "손절·익절가를 미리 걸어두면 목표가 도달 시 자동으로 주문이 실행됩니다. 하나가 체결되면 나머지를 취소하는 OCO 주문도 지원합니다." },
  {
    icon: "zap", title: "AI 주문 제안",
    desc: "종목별로 AI가 매수·매도 방향을 제안합니다. 승인해도 바로 주문되지 않고, 주문 폼에서 직접 확인한 뒤 실행합니다.",
    note: "AI 제안은 투자자문이 아니며, 참고용 시뮬레이션 정보입니다.",
  },
];

const NEXT_LABEL = ["다음 — 관심 분야 고르기", "다음 — 관심종목 고르기", "다음", "시작하기"];

function choiceClass(on: boolean) {
  return on
    ? "border-[1.5px] border-dracula-purple bg-[#3a2f52] text-dracula-fg font-semibold"
    : "border border-tm-line2 bg-tm-panel text-tm-soft hover:text-dracula-fg";
}

export default function OnboardingPage() {
  const router = useRouter();
  const [step, setStep] = useState(0);
  // 관심 분야·사용 방식 선택은 아직 저장할 곳이 없다(서버 API 없음) — 화면 안에서만 유지한다.
  const [sectors, setSectors] = useState<string[]>([]);
  const [style, setStyle] = useState<string | null>(null);
  const isLast = step === TOTAL - 1;

  const finish = () => {
    localStorage.setItem("onboarding_done", "1");
    router.replace("/");
  };
  const next = () => (isLast ? finish() : setStep((s) => s + 1));

  return (
    <div className="flex min-h-screen justify-center bg-tm-page px-6 pb-12 pt-8 text-sm text-dracula-fg">
      <div className="flex w-full max-w-[760px] flex-col gap-8">
        <div className="flex items-center justify-between">
          <BrandLink size={26} />
          {!isLast && (
            <button type="button" onClick={finish} className="text-13 text-tm-muted hover:text-dracula-fg">
              건너뛰기
            </button>
          )}
        </div>

        <div className="flex flex-col gap-2.5">
          <div className="flex gap-1.5" aria-hidden>
            {Array.from({ length: TOTAL }).map((_, i) => (
              <span key={i} className={cn("h-1 flex-1 rounded-full", i <= step ? "bg-dracula-purple" : "bg-tm-line2")} />
            ))}
          </div>
          <span className="num text-xs text-tm-muted" aria-label={`${TOTAL}단계 중 ${step + 1}단계`}>
            {step + 1} / {TOTAL}
          </span>
        </div>

        <div key={step} className="flex flex-col gap-8">
          {step === 0 && (
            <>
              <Title title="monticker에 오신 것을 환영합니다" desc="실시간 시세와 이벤트 타임라인, 모의투자와 퀀트랩까지 — 한 곳에서 관찰하고 검증하세요." />
              <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(220px,1fr))" }}>
                {FEATURES.map((f) => (
                  <div key={f.title} className="flex flex-col gap-1.5 rounded-xl border border-tm-line2 bg-tm-panel p-4">
                    <Icon name={f.icon} size={22} className="text-dracula-purple" />
                    <span className="text-15 font-bold">{f.title}</span>
                    <span className="text-13 leading-normal text-tm-muted">{f.desc}</span>
                    {f.note && <span className="text-xs leading-normal text-tm-muted">{f.note}</span>}
                  </div>
                ))}
              </div>
            </>
          )}

          {step === 1 && (
            <>
              <Title
                title="어떤 시장을 지켜볼까요?"
                desc="고른 분야의 이벤트가 홈과 알림에 먼저 올라옵니다. 나중에 언제든 바꿀 수 있어요."
                tag
              />
              <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(150px,1fr))" }}>
                {SECTORS.map((s) => {
                  const on = sectors.includes(s);
                  return (
                    <button
                      key={s}
                      type="button"
                      aria-pressed={on}
                      onClick={() => setSectors((v) => (on ? v.filter((x) => x !== s) : [...v, s]))}
                      className={cn("h-12 rounded-[10px] text-sm", choiceClass(on))}
                    >
                      {s}
                    </button>
                  );
                })}
              </div>

              <div className="flex flex-col gap-3">
                <h2 className="m-0 text-[1.0625rem] font-bold">어떻게 쓰고 싶으세요?</h2>
                <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(200px,1fr))" }}>
                  {STYLES.map((s) => {
                    const on = style === s.key;
                    return (
                      <button
                        key={s.key}
                        type="button"
                        aria-pressed={on}
                        onClick={() => setStyle(on ? null : s.key)}
                        className={cn("flex flex-col gap-1.5 rounded-xl p-4 text-left", choiceClass(on), "font-normal text-dracula-fg")}
                      >
                        <Icon name={s.icon} size={22} className="text-dracula-purple" />
                        <span className="text-15 font-bold">{s.title}</span>
                        <span className="text-13 leading-normal text-tm-muted">{s.desc}</span>
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="flex flex-col gap-3">
                <h2 className="m-0 text-[1.0625rem] font-bold">모의투자 시작 자금</h2>
                <div className="flex flex-wrap items-center gap-2">
                  <div className="inline-flex gap-0.5 rounded-lg bg-tm-inner p-[3px]">
                    {CAPITAL.map((c) => (
                      <button
                        key={c.key}
                        type="button"
                        aria-pressed={c.available}
                        disabled={!c.available}
                        title={c.available ? undefined : "준비 중"}
                        className={cn(
                          "h-[34px] whitespace-nowrap rounded-md px-3 text-13 disabled:cursor-not-allowed disabled:opacity-50",
                          c.available ? "bg-tm-line2 font-semibold text-dracula-fg" : "text-tm-muted",
                        )}
                      >
                        {c.label}
                      </button>
                    ))}
                  </div>
                  <PreviewTag />
                </div>
                <span className="text-xs text-tm-muted">가상의 돈입니다. 실제 계좌 연동은 원할 때 따로 진행합니다.</span>
              </div>
            </>
          )}

          {step === 2 && (
            <>
              <Title title="관심종목을 담아보세요" desc="종목 검색에서 ☆를 누르면 관심종목에 추가됩니다. 관심종목의 이벤트와 가격 알림이 홈에 모입니다." />
              <div className="flex flex-col items-start gap-3 rounded-xl border border-tm-line2 bg-tm-panel p-5">
                <Icon name="search" size={22} className="text-dracula-purple" />
                <span className="text-15 font-bold">종목·이벤트·전략 검색</span>
                <span className="text-13 text-tm-muted">⌘K(Ctrl+K)로 어디서든 검색을 열 수 있어요.</span>
                <Link href="/stocks/search" className="text-13 text-dracula-purple hover:text-[#d6bcfb]" onClick={() => localStorage.setItem("onboarding_done", "1")}>
                  지금 종목 검색으로 이동 →
                </Link>
              </div>
            </>
          )}

          {step === 3 && (
            <>
              <Title title="준비 완료!" desc="지금 바로 시장을 탐색해보세요." />
              <div className="grid h-24 w-24 place-items-center rounded-2xl bg-tm-panel text-dracula-purple">
                <Icon name="check" size={44} strokeWidth={2.4} />
              </div>
            </>
          )}
        </div>

        <div className="flex justify-between gap-2 border-t border-tm-line pt-2">
          {step > 0 ? (
            <Btn kind="ghost" size="xl" onClick={() => setStep((s) => s - 1)}>이전</Btn>
          ) : (
            <span />
          )}
          <Btn size="xl" onClick={next}>{NEXT_LABEL[step]}</Btn>
        </div>
      </div>
    </div>
  );
}

function Title({ title, desc, tag }: { title: string; desc: string; tag?: boolean }) {
  return (
    <div className="flex flex-col gap-2">
      <h1 className="m-0 flex flex-wrap items-center gap-2 text-[1.75rem] font-bold tracking-[-0.02em]">
        {title}
        {tag && <PreviewTag />}
      </h1>
      <p className="m-0 text-tm-soft">{desc}</p>
    </div>
  );
}
