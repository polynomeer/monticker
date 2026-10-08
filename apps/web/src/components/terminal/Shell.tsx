"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/useAuth";
import { useConsentStatus } from "@/hooks/useConsents";
import { useAccountChipSummary } from "@/hooks/useAccountSummary";
import { Icon, type IconName } from "./Icon";
import { IconBtn } from "./ui";
import { FlashValue } from "./FlashValue";

// ── 아이콘 레일 ────────────────────────────────────────────────────────
interface RailItem {
  label: string;
  icon: IconName;
  href: string;
  /** 이 경로들 아래에 있으면 활성 */
  match: string[];
}

const RAIL: (RailItem | null)[] = [
  { label: "홈", icon: "home", href: "/", match: ["/"] },
  { label: "트레이딩", icon: "candles", href: "/stocks/search", match: ["/stocks", "/compare"] },
  { label: "스크리너", icon: "filter", href: "/screener", match: ["/screener"] },
  { label: "관심종목", icon: "star", href: "/watchlist", match: ["/watchlist"] },
  { label: "포트폴리오", icon: "pie", href: "/portfolio", match: ["/portfolio", "/matching", "/watch-rules"] },
  { label: "지갑", icon: "wallet", href: "/wallet", match: ["/wallet", "/settlement"] },
  { label: "퀀트랩", icon: "flask", href: "/quant-lab", match: ["/quant-lab", "/backtest", "/analytics"] },
  { label: "전략 마켓", icon: "store", href: "/quant-lab/market", match: ["/quant-lab/market", "/quant-lab/earnings"] },
  { label: "실전투자", icon: "bank", href: "/brokerage", match: ["/brokerage"] },
  { label: "리스크", icon: "shield", href: "/risk", match: ["/risk"] },
  null,
  { label: "알림", icon: "bell", href: "/alerts", match: ["/alerts"] },
  { label: "설정", icon: "sliders", href: "/settings/appearance", match: ["/settings", "/subscription"] },
];

/** 가장 구체적인(긴) match가 이기도록 — /quant-lab/market은 퀀트랩이 아니라 전략 마켓 */
function activeRail(pathname: string) {
  let best: { label: string; len: number } | null = null;
  for (const it of RAIL) {
    if (!it) continue;
    for (const m of it.match) {
      const hit = m === "/" ? pathname === "/" : pathname === m || pathname.startsWith(m + "/");
      if (hit && (!best || m.length > best.len)) best = { label: it.label, len: m.length };
    }
  }
  return best?.label;
}

function Rail() {
  const pathname = usePathname() ?? "/";
  const active = activeRail(pathname);
  return (
    <nav aria-label="주 메뉴" className="flex flex-[0_0_56px] flex-col items-center gap-1.5 border-r border-tm-line py-2">
      {RAIL.map((it, i) =>
        it === null ? (
          <span key={`sep${i}`} className="my-1.5 h-px w-7 bg-tm-line" />
        ) : (
          <Link
            key={it.href}
            href={it.href}
            aria-label={it.label}
            title={it.label}
            aria-current={it.label === active ? "page" : undefined}
            className={cn(
              "grid h-10 w-10 place-items-center rounded-[10px]",
              it.label === active ? "bg-tm-raised text-dracula-purple" : "text-tm-muted hover:bg-tm-raised/60 hover:text-dracula-fg",
            )}
          >
            <Icon name={it.icon} size={20} />
          </Link>
        ),
      )}
    </nav>
  );
}

// ── 상단 바 ────────────────────────────────────────────────────────────
export function LogoMark({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 26 26" fill="none" aria-hidden className="text-dracula-purple">
      <rect x="1" y="1" width="24" height="24" rx="7" stroke="currentColor" strokeWidth="2" />
      <path d="M6 17 L10 12 L13 14 L19 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="19" cy="7" r="2.2" fill="currentColor" />
    </svg>
  );
}

export interface TopStat {
  label: string;
  value: ReactNode;
  /** text-* 클래스(예: "text-up") */
  tone?: string;
  /** 지표 정의 — 마우스를 올리면 보인다(title) */
  hint?: string;
  /** 실시간 가격 — 이 숫자가 바뀌면 값이 잠깐 깜빡인다(접근성 설정의 "가격 변동 깜빡임"·움직임 줄이기를 따른다) */
  flash?: number | null;
}

export function TitleBlock({ title, crumb }: { title: ReactNode; crumb?: ReactNode }) {
  return (
    <div className="flex min-w-[120px] flex-col gap-px">
      {crumb && <span className="text-2xs text-tm-muted">{crumb}</span>}
      <h1 className="m-0 whitespace-nowrap text-base font-bold text-dracula-fg">{title}</h1>
    </div>
  );
}

/** 종목 화면 상단의 종목 선택 버튼 — 누르면 검색으로 */
export function SymbolPill({ name, code, market }: { name: string; code?: string; market?: string }) {
  return (
    <Link href="/stocks/search" className="flex h-9 items-center gap-2.5 rounded-lg bg-tm-panel px-2.5 text-dracula-fg hover:bg-tm-raised">
      <Icon name="search" size={16} className="text-tm-muted" />
      <span className="grid h-5 w-5 place-items-center rounded-md bg-dracula-purple text-2xs font-bold text-tm-page">{name.slice(0, 1)}</span>
      <h1 className="m-0 text-sm font-bold">{name}</h1>
      {code && <span className="num text-2xs text-tm-muted">{code}</span>}
      {market && <span className="rounded bg-tm-raised px-1.5 py-px text-2xs text-tm-soft">{market}</span>}
      <span className="num ml-1.5 text-2xs text-tm-muted">⌘K</span>
    </Link>
  );
}

export function SearchPill() {
  return (
    <Link href="/stocks/search" className="flex h-9 min-w-[220px] items-center gap-2.5 rounded-lg bg-tm-panel px-3 text-13 text-tm-muted hover:text-dracula-fg">
      <Icon name="search" size={16} />
      <span className="flex-1">종목·이벤트·전략 검색</span>
      <span className="num text-2xs">⌘K</span>
    </Link>
  );
}

function UserMenu() {
  const { isLoggedIn, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const pathname = usePathname();
  useEffect(() => setOpen(false), [pathname]);
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [open]);

  if (!isLoggedIn) {
    return (
      <Link href="/login" className="inline-flex h-9 items-center rounded-lg bg-dracula-purple px-3.5 text-13 font-bold text-tm-page">
        로그인
      </Link>
    );
  }
  return (
    <div className="relative" ref={ref}>
      <IconBtn name="user" label="내 계정" size={36} iconSize={18} aria-expanded={open} onClick={() => setOpen((v) => !v)} />
      {open && (
        <div className="absolute right-0 top-full z-50 mt-2 w-44 overflow-hidden rounded-[10px] border border-tm-line2 bg-tm-panel py-1 shadow-glow-line">
          {[
            { href: "/subscription", label: "구독", icon: "card" as const },
            { href: "/settings/appearance", label: "설정", icon: "sliders" as const },
          ].map((l) => (
            <Link key={l.href} href={l.href} className="flex items-center gap-2.5 px-4 py-2.5 text-13 text-dracula-fg hover:bg-tm-raised">
              <Icon name={l.icon} size={16} />
              {l.label}
            </Link>
          ))}
          <div className="my-1 h-px bg-tm-line" />
          <button type="button" onClick={logout} className="flex w-full items-center gap-2.5 px-4 py-2.5 text-left text-13 text-down hover:bg-tm-raised">
            <Icon name="x" size={16} />
            로그아웃
          </button>
        </div>
      )}
    </div>
  );
}

export interface AccountChip {
  /** 실전(live)이면 주황 표시. 잔액은 셸이 공유 쿼리로 채운다(모의 총자산·실계좌 가용 현금) */
  kind: "paper" | "live";
}

function TopBar({ left, stats, account }: { left: ReactNode; stats: TopStat[]; account: AccountChip }) {
  const live = account.kind === "live";
  const { isLoggedIn } = useAuth();
  const chip = useAccountChipSummary(account.kind, isLoggedIn);
  return (
    <header className="flex flex-wrap items-center gap-x-6 gap-y-2.5 border-b border-tm-line bg-tm-page py-2.5 pl-[15px] pr-4">
      <Link href="/" aria-label="monticker 홈" className="grid w-[26px] place-items-center">
        <LogoMark />
      </Link>
      {left}
      <div className="flex min-w-0 flex-[1_1_300px] flex-wrap gap-x-[26px] gap-y-2">
        {stats.map((s) => (
          <div key={s.label} className="flex min-w-0 flex-col gap-0.5" title={s.hint}>
            <span className="whitespace-nowrap text-[0.65625rem] tracking-[0.04em] text-tm-muted">{s.label}</span>
            <span className={cn("num whitespace-nowrap text-13", s.tone ?? "text-dracula-fg")}>
              {s.flash !== undefined ? <FlashValue value={s.flash}>{s.value}</FlashValue> : s.value}
            </span>
          </div>
        ))}
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        {/* 레이아웃 편집·전체 메뉴 — 시안 요소, 기능은 docs/design-rollout-plan.md */}
        <IconBtn name="layout" label="레이아웃 편집 (준비 중)" size={36} iconSize={18} aria-disabled="true" />
        <Link
          href={live ? "/brokerage" : "/wallet"}
          data-account={account.kind}
          className={cn(
            "flex h-9 items-center gap-2 rounded-lg border px-3 text-13 text-dracula-fg hover:bg-tm-raised",
            live ? "border-dracula-orange/60" : "border-tm-line2",
          )}
        >
          <span aria-hidden className={cn("h-[7px] w-[7px] rounded-full", live ? "bg-dracula-orange" : "bg-dracula-yellow")} />
          {live ? "실전" : "모의투자"} 계좌
          {chip.amount !== undefined && (
            <span className={cn("num", live ? "text-dracula-orange" : "text-tm-muted")} title={chip.amountLabel}>
              <span className="sr-only">{chip.amountLabel} </span>
              {chip.amount}
            </span>
          )}
          <Icon name="chev" size={14} className="text-tm-muted" />
        </Link>
        <IconBtn name="grid" label="전체 메뉴 (준비 중)" size={36} iconSize={18} aria-disabled="true" />
        <Link href="/alerts" aria-label="알림" className="grid h-9 w-9 place-items-center rounded-lg text-tm-muted hover:bg-tm-raised hover:text-dracula-fg">
          <Icon name="bell" size={18} />
        </Link>
        <UserMenu />
      </div>
    </header>
  );
}

/**
 * ADR-068 — 가입 필수 동의가 빠진 사용자(소셜 가입, 약관 개정)는 앱 화면 대신 동의 화면으로 보낸다.
 * 서버는 동의 없이도 조회 API를 막지 않으므로 이 화면 단계가 실제 게이트다.
 */
function useConsentGate() {
  const { isLoggedIn } = useAuth();
  const { data } = useConsentStatus(isLoggedIn);
  const router = useRouter();
  const pathname = usePathname() ?? "/";
  useEffect(() => {
    if (data && data.missingRequired.length > 0) router.replace(`/consent?next=${encodeURIComponent(pathname)}`);
  }, [data, pathname, router]);
}

/** ⌘K / Ctrl+K → 검색. 입력 중일 때는 가로채지 않는다. */
function useSearchShortcut() {
  const router = useRouter();
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        router.push("/stocks/search");
      }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [router]);
}

/**
 * 터미널 화면 골격 — 상단 바(로고·제목/종목·시세 스트립·계좌·알림·계정) + 왼쪽 아이콘 레일 + 본문.
 * 모든 앱 화면은 이걸로 감싼다. 로그인·약관 같은 바깥 화면은 쓰지 않는다.
 */
export function TerminalPage({
  title, crumb, left, stats = [], account = { kind: "paper" }, children, className,
}: {
  title?: ReactNode;
  crumb?: ReactNode;
  /** title 대신 왼쪽에 둘 요소(SymbolPill, SearchPill) */
  left?: ReactNode;
  stats?: TopStat[];
  account?: AccountChip;
  children: ReactNode;
  className?: string;
}) {
  useSearchShortcut();
  useConsentGate();
  return (
    <div className="flex min-h-screen flex-col bg-tm-page text-13 text-dracula-fg">
      <TopBar left={left ?? <TitleBlock title={title} crumb={crumb} />} stats={stats} account={account} />
      <div className="flex min-h-0 flex-1">
        <Rail />
        <main className={cn("flex min-w-0 flex-auto flex-col gap-2 p-2", className)}>{children}</main>
      </div>
    </div>
  );
}
