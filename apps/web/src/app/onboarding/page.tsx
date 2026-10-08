"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Btn, Icon, Notice, PreviewTag, type IconName } from "@/components/terminal";
import { BrandLink } from "@/components/auth/AuthShell";
import { cn } from "@/lib/utils";
import { getAccessToken } from "@/services/auth";
import { authFetch } from "@/services/api";
import {
  PAPER_INITIAL_CAPITALS, fetchPaperAccount, fetchPreferences, openPaperAccount, savePreferences,
  type InterestSector, type PaperAccountInfo, type PaperInitialCapital, type UsageStyle,
} from "@/services/onboarding";
import { INTEREST_SECTORS } from "@/lib/interestSectors";
import { BULK_WATCHLIST_MAX, addStocksToWatchlist, type BulkAddResult, type WatchlistGroupLite } from "@/lib/watchlistBulk";

const TOTAL = 4;

/** 키는 서버 enum(V86 CHECK)과 같다. 라벨·업종 매핑은 한 곳(lib/interestSectors, ADR-099)에서 가져온다. */
const SECTORS = INTEREST_SECTORS;

const STYLES: { key: UsageStyle; icon: IconName; title: string; desc: string }[] = [
  { key: "OBSERVE", icon: "eye", title: "관찰 위주", desc: "차트와 이벤트를 먼저 익히고 싶어요" },
  { key: "EVENT_TRADING", icon: "trend", title: "단기 이벤트 매매", desc: "급등·급락 신호에 빠르게 반응하고 싶어요" },
  { key: "QUANT", icon: "flask", title: "규칙 기반 퀀트", desc: "전략을 만들고 검증하고 싶어요" },
];

/** 모의투자 시작 자금 — 서버 화이트리스트(ADR-089)와 같은 세 값. 계좌를 처음 만들 때만 정할 수 있다. */
const CAPITAL_LABEL: Record<PaperInitialCapital, string> = {
  10_000_000: "1,000만원",
  30_000_000: "3,000만원",
  100_000_000: "1억원",
};

/** 3단계 목록 — 인기도 통계가 없어 거래대금 상위 종목을 쓴다(화면에 그렇게 밝힌다). */
const POPULAR_LIMIT = 12;
interface PopularStock { stockId: number; symbol: string; name: string; changeRate: number }

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
  const qc = useQueryClient();
  const [step, setStep] = useState(0);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  // ADR-089 — 관심 분야·사용 방식은 PUT /api/users/me/preferences로 저장한다. ADR-099 — 홈·알림은 관심 분야를 정렬·강조 신호로만 쓴다.
  const [sectors, setSectors] = useState<InterestSector[]>([]);
  const [style, setStyle] = useState<UsageStyle | null>(null);
  const [capital, setCapital] = useState<PaperInitialCapital>(10_000_000);
  const [account, setAccount] = useState<PaperAccountInfo | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const touched = useRef(false);
  const isLast = step === TOTAL - 1;

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  // 저장해 둔 선택과 기존 모의 계좌를 불러온다. 사용자가 먼저 손댔으면 덮어쓰지 않는다.
  useEffect(() => {
    if (!isLoggedIn) return;
    let cancelled = false;
    fetchPreferences()
      .then((p) => {
        if (cancelled || !p || touched.current) return;
        setSectors(p.interestSectors);
        setStyle(p.usageStyle);
      })
      .catch(() => {});
    fetchPaperAccount()
      .then((a) => {
        if (cancelled || !a) return;
        setAccount(a);
        const c = PAPER_INITIAL_CAPITALS.find((v) => v === Number(a.initialCapital));
        if (c) setCapital(c);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [isLoggedIn]);

  const finish = () => {
    localStorage.setItem("onboarding_done", "1");
    router.replace("/");
  };

  /** 관심 분야 단계를 넘어갈 때 저장한다 — 실패하면 머문다(건너뛰기는 언제든 가능). */
  const saveStep1 = async (): Promise<boolean> => {
    if (!isLoggedIn) return true;
    setSaving(true);
    setSaveError(null);
    try {
      const saved = await savePreferences(sectors, style);
      qc.setQueryData(["users", "me", "preferences"], saved); // 홈·알림의 관심 분야 순(ADR-099)이 바로 반영되게
      if (!account) {
        const opened = await openPaperAccount(capital);
        setAccount(opened);
        qc.invalidateQueries({ queryKey: ["paper"] });
      }
      return true;
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : "저장하지 못했습니다.");
      return false;
    } finally {
      setSaving(false);
    }
  };

  const next = async () => {
    if (isLast) return finish();
    if (step === 1 && !(await saveStep1())) return;
    setStep((s) => s + 1);
  };

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
                desc="고른 분야와 사용 방식은 계정에 저장되고, 설정 › 관심 분야에서 언제든 바꿀 수 있어요. 홈 섹터·이벤트는 고른 분야를 앞에 두고 표시합니다(끌 수 있고, 숨기는 정보는 없어요)."
              />
              <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(150px,1fr))" }}>
                {SECTORS.map((s) => {
                  const on = sectors.includes(s.key);
                  return (
                    <button
                      key={s.key}
                      type="button"
                      aria-pressed={on}
                      onClick={() => {
                        touched.current = true;
                        setSectors((v) => (on ? v.filter((x) => x !== s.key) : [...v, s.key]));
                      }}
                      className={cn("h-12 rounded-[10px] text-sm", choiceClass(on))}
                    >
                      {s.label}
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
                        onClick={() => {
                          touched.current = true;
                          setStyle(on ? null : s.key);
                        }}
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
                <div role="radiogroup" aria-label="모의투자 시작 자금" className="inline-flex w-fit gap-0.5 rounded-lg bg-tm-inner p-[3px]">
                  {PAPER_INITIAL_CAPITALS.map((c) => {
                    const on = capital === c;
                    // 이미 계좌가 있으면 시작 자금은 바꿀 수 없다(서버도 409) — 다른 값은 잠근다
                    const locked = !!account && !on;
                    return (
                      <button
                        key={c}
                        type="button"
                        role="radio"
                        aria-checked={on}
                        disabled={locked}
                        onClick={() => setCapital(c)}
                        className={cn(
                          "h-[34px] whitespace-nowrap rounded-md px-3 text-13 disabled:cursor-not-allowed disabled:opacity-50",
                          on ? "bg-tm-line2 font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg",
                        )}
                      >
                        {CAPITAL_LABEL[c]}
                      </button>
                    );
                  })}
                </div>
                {account ? (
                  <span className="text-xs text-tm-muted">
                    이미 시작 자금 {CAPITAL_LABEL[capital]}으로 만든 모의 계좌가 있습니다. 시작 자금은 계좌를 처음 만들 때만 정할 수 있어요.
                  </span>
                ) : (
                  <span className="text-xs text-tm-muted">가상의 돈입니다. 실제 계좌 연동은 원할 때 따로 진행합니다. 시작 자금은 처음 한 번만 정할 수 있어요.</span>
                )}
              </div>
              {!isLoggedIn && <Notice tone="info">로그인하면 선택이 계정에 저장되고 모의 계좌가 만들어집니다.</Notice>}
              {saveError && <Notice tone="danger">{saveError}</Notice>}
            </>
          )}

          {step === 2 && <PickStocksStep isLoggedIn={isLoggedIn} />}

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
          <Btn size="xl" onClick={next} disabled={saving}>{saving ? "저장 중..." : NEXT_LABEL[step]}</Btn>
        </div>
      </div>
    </div>
  );
}

/**
 * 3단계 — 거래대금 상위 종목에서 골라 관심종목에 한 번에 담는다. 이미 담긴 종목은 "담김"으로 잠그고 다시 요청하지 않는다.
 * 서버에 일괄 추가 API가 없어 단건 API를 순서대로 부르고(lib/watchlistBulk), 실패한 종목은 이름으로 알려 준다.
 */
function PickStocksStep({ isLoggedIn }: { isLoggedIn: boolean }) {
  const qc = useQueryClient();
  const [selected, setSelected] = useState<number[]>([]);
  const [adding, setAdding] = useState(false);
  const [result, setResult] = useState<BulkAddResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: popular = [], isLoading } = useQuery<PopularStock[]>({
    queryKey: ["screener", "onboarding-popular", POPULAR_LIMIT],
    queryFn: async () => {
      const r = await fetch(`/api/screener?tab=realtime&market=all&sort=amount&limit=${POPULAR_LIMIT}`);
      if (!r.ok) return [];
      return (await r.json())?.items ?? [];
    },
    staleTime: 60_000,
  });

  const { data: groups = [] } = useQuery<WatchlistGroupLite[]>({
    queryKey: ["watchlist", "groups"],
    queryFn: async () => {
      const r = await authFetch("/api/watchlists");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
    staleTime: 15_000,
  });
  const watched = new Set(groups.flatMap((g) => g.items.map((i) => i.stockId)));
  const nameOf = (id: number) => popular.find((p) => p.stockId === id)?.name ?? String(id);

  const toggle = (id: number) =>
    setSelected((v) => (v.includes(id) ? v.filter((x) => x !== id) : v.length >= BULK_WATCHLIST_MAX ? v : [...v, id]));

  const addAll = async () => {
    setAdding(true);
    setError(null);
    setResult(null);
    try {
      const r = await addStocksToWatchlist(selected, authFetch);
      setResult(r);
      setSelected(r.failed); // 실패한 것만 선택에 남겨 다시 시도할 수 있게
    } catch (e) {
      setError(e instanceof Error ? e.message : "관심종목에 담지 못했습니다.");
    } finally {
      setAdding(false);
      qc.invalidateQueries({ queryKey: ["watchlist"] });
    }
  };

  return (
    <>
      <Title
        title="관심종목을 담아보세요"
        desc="관심종목의 이벤트와 가격 알림이 홈에 모입니다. 아래에서 골라 한 번에 담거나, 종목 검색에서 ☆를 눌러 추가할 수 있어요."
      />
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="m-0 text-[1.0625rem] font-bold">거래대금 상위 종목</h2>
          <span className="text-xs text-tm-muted">인기 순위 통계가 아직 없어 거래대금 순으로 보여 줍니다. 투자 추천이 아닙니다.</span>
        </div>
        {isLoading ? (
          <div className="h-40 animate-pulse rounded-xl bg-tm-inner" />
        ) : popular.length === 0 ? (
          <p className="m-0 text-13 text-tm-muted">지금은 시세 데이터가 없어 보여 줄 종목이 없습니다. 종목 검색에서 직접 담아 보세요.</p>
        ) : (
          <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(170px,1fr))" }}>
            {popular.map((p) => {
              const isWatched = watched.has(p.stockId);
              const on = isWatched || selected.includes(p.stockId);
              return (
                <button
                  key={p.stockId}
                  type="button"
                  aria-pressed={on}
                  disabled={isWatched || !isLoggedIn}
                  onClick={() => toggle(p.stockId)}
                  className={cn("flex flex-col items-start gap-0.5 rounded-[10px] px-3 py-2.5 text-left disabled:cursor-default", choiceClass(on))}
                >
                  <span className="flex w-full items-center justify-between gap-2">
                    <span className="truncate text-sm">{p.name}</span>
                    {isWatched && <span className="text-2xs text-dracula-green">담김</span>}
                  </span>
                  <span className="flex w-full items-center justify-between gap-2 text-xs font-normal">
                    <span className="num text-tm-muted">{p.symbol}</span>
                    <span className={cn("num", p.changeRate >= 0 ? "text-up" : "text-down")}>
                      {p.changeRate >= 0 ? "+" : ""}
                      {p.changeRate.toFixed(2)}%
                    </span>
                  </span>
                </button>
              );
            })}
          </div>
        )}
        {isLoggedIn ? (
          <div className="flex flex-wrap items-center gap-3">
            <Btn onClick={addAll} disabled={adding || selected.length === 0}>
              {adding ? "담는 중..." : `선택한 ${selected.length}개 관심종목에 담기`}
            </Btn>
            <Link href="/stocks/search" className="text-13 text-dracula-purple hover:text-[#d6bcfb]" onClick={() => localStorage.setItem("onboarding_done", "1")}>
              종목 검색으로 이동 →
            </Link>
          </div>
        ) : (
          <Notice tone="info">로그인하면 관심종목에 담을 수 있습니다.</Notice>
        )}
        {result && (
          <Notice tone={result.failed.length > 0 ? "warn" : "ok"}>
            {result.added.length}개를 담았습니다
            {result.alreadyWatched.length > 0 && ` · ${result.alreadyWatched.length}개는 이미 관심종목에 있습니다`}
            {result.failed.length > 0 && ` · ${result.failed.length}개 실패(${result.failed.map(nameOf).join(", ")}) — 다시 시도해 주세요`}
          </Notice>
        )}
        {error && <Notice tone="danger">{error}</Notice>}
      </div>
    </>
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
