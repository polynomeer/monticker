"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/useAuth";
import { useConsentStatus } from "@/hooks/useConsents";
import { Icon } from "./Icon";
import { IconBtn } from "./ui";
import { FlashValue } from "./FlashValue";
import { HeaderSearch, type HeaderSearchHandle } from "./HeaderSearch";
import { StatusPill } from "./StatusPill";
import { activeRail, RAIL, sectionTabs } from "./nav";

// ── 라벨 레일 (ADR-112) ────────────────────────────────────────────────
function Rail() {
  const pathname = usePathname() ?? "/";
  const active = activeRail(pathname)?.label;
  const link = (it: (typeof RAIL)[number]) => (
    <Link
      key={it.href}
      href={it.href}
      aria-current={it.label === active ? "page" : undefined}
      className={cn(
        "flex w-[60px] flex-col items-center gap-1 rounded-[10px] py-2 text-[0.6875rem] leading-tight",
        it.label === active ? "bg-tm-raised text-dracula-purple" : "text-tm-muted hover:bg-tm-raised/60 hover:text-dracula-fg",
      )}
    >
      <Icon name={it.icon} size={20} />
      <span className="whitespace-nowrap">{it.label}</span>
    </Link>
  );
  return (
    <nav aria-label="주 메뉴" className="flex flex-[0_0_72px] flex-col items-center gap-1 border-r border-tm-line py-2">
      {RAIL.filter((it) => !it.bottom).map((it) => (
        <div key={it.href} className="contents">
          {it.divider && <span aria-hidden className="my-1 h-px w-8 bg-tm-line" />}
          {link(it)}
        </div>
      ))}
      <div className="mt-auto flex flex-col items-center">{RAIL.filter((it) => it.bottom).map(link)}</div>
    </nav>
  );
}

/** 묶인 화면들 사이를 오가는 본문 첫 줄 탭. 셸이 지금 주소로 고른다 — 화면 코드는 신경 쓰지 않는다 */
function SectionTabs() {
  const pathname = usePathname() ?? "/";
  const section = sectionTabs(pathname);
  if (!section) return null;
  return (
    <nav aria-label={`${section.item.label} 구역`} className="flex flex-none gap-1 overflow-x-auto border-b border-tm-line px-1">
      {section.item.tabs!.map((t) => {
        const on = t === section.active;
        return (
          <Link
            key={t.href}
            href={t.href}
            aria-current={on ? "page" : undefined}
            className={cn(
              "-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-13",
              on ? "border-dracula-purple font-semibold text-dracula-fg" : "border-transparent text-tm-muted hover:text-dracula-fg",
            )}
          >
            {t.label}
          </Link>
        );
      })}
    </nav>
  );
}

// ── 상단 바 ────────────────────────────────────────────────────────────
/**
 * monticker 마크 — 몬스터 몸 + 눈 둘 + 차트선 입(끝 핑크 점 = 현재가). 원본: assets/brand/mark.svg
 * 눈·입은 마스크로 뚫어 어떤 배경 위에서도 배경색이 비친다. 몸 색은 currentColor.
 */
export function LogoMark({ size = 26 }: { size?: number }) {
  const maskId = `mt-logo-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" aria-hidden className="text-dracula-purple">
      <mask id={maskId}>
        <rect width="100" height="100" fill="#fff" />
        <circle cx="40" cy="44" r="5.5" fill="#000" />
        <circle cx="60" cy="44" r="5.5" fill="#000" />
        <polyline points="36,62 43,58 49,61 56,55 63,56" fill="none" stroke="#000" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
      </mask>
      <path d="M18 50 a32 32 0 0 1 64 0 V86 L71 76 L60 86 L50 76 L40 86 L29 76 L18 86 Z" fill="currentColor" mask={`url(#${maskId})`} />
      <circle cx="64.5" cy="55.5" r="4" className="fill-dracula-pink" />
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
    <div className="flex min-w-0 flex-none flex-col gap-px">
      {crumb && <span className="whitespace-nowrap text-2xs text-tm-muted">{crumb}</span>}
      <h1 className="m-0 whitespace-nowrap text-base font-bold text-dracula-fg">{title}</h1>
    </div>
  );
}

/** 종목 화면 상단의 종목 이름표. 검색은 상단 검색창 하나라 돋보기·⌘K는 두지 않는다(ADR-112) */
export function SymbolPill({ name, code, market }: { name: string; code?: string; market?: string }) {
  return (
    <div className="flex h-9 flex-none items-center gap-2.5 whitespace-nowrap rounded-lg bg-tm-panel px-2.5 text-dracula-fg">
      <span className="grid h-5 w-5 place-items-center rounded-md bg-dracula-purple text-2xs font-bold text-tm-on-accent">{name.slice(0, 1)}</span>
      <h1 className="m-0 text-sm font-bold">{name}</h1>
      {code && <span className="num text-2xs text-tm-muted">{code}</span>}
      {market && <span className="rounded-sm bg-tm-raised px-1.5 py-px text-2xs text-tm-soft">{market}</span>}
    </div>
  );
}

function StatCell({ s }: { s: TopStat }) {
  return (
    <div className="flex flex-none flex-col gap-0.5" title={s.hint}>
      <span className="whitespace-nowrap text-[0.65625rem] tracking-[0.04em] text-tm-muted">{s.label}</span>
      <span className={cn("num whitespace-nowrap text-13", s.tone ?? "text-dracula-fg")}>
        {s.flash !== undefined ? <FlashValue value={s.flash}>{s.value}</FlashValue> : s.value}
      </span>
    </div>
  );
}

const STAT_GAP = 26;
const MORE_WIDTH = 72;

/** 몇 개가 한 줄에 들어가는지 — 넘치면 뒤에서부터 "+N 지표"로 접는다. 순수 함수라 테스트한다 */
export function fitCount(widths: number[], available: number, gap = STAT_GAP, more = MORE_WIDTH): number {
  const total = widths.reduce((a, w, i) => a + w + (i ? gap : 0), 0);
  if (total <= available) return widths.length;
  let used = 0;
  for (let i = 0; i < widths.length; i++) {
    const next = used + widths[i] + (i ? gap : 0);
    if (next + gap + more > available) return i;
    used = next;
  }
  return widths.length;
}

/** 지표 칸 — 한 줄 고정(ADR-112). 들어가는 만큼 보이고 나머지는 "+N 지표" 펼침에 둔다 */
function TopStats({ stats }: { stats: TopStat[] }) {
  const boxRef = useRef<HTMLDivElement>(null);
  const measureRef = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState(stats.length);
  const [open, setOpen] = useState(false);
  const key = stats.map((s) => s.label).join("|");

  useLayoutEffect(() => {
    const box = boxRef.current;
    const measure = measureRef.current;
    if (!box || !measure) return;
    const update = () => {
      const widths = Array.from(measure.children).map((c) => (c as HTMLElement).offsetWidth);
      setShown(fitCount(widths, box.clientWidth));
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(box);
    return () => ro.disconnect();
  }, [key]);

  const hidden = stats.slice(shown);
  return (
    <div ref={boxRef} className="relative flex min-w-0 flex-1 items-center gap-[26px] overflow-hidden">
      {/* 너비 재기용 사본 — 화면·스크린리더에 나오지 않는다 */}
      <div ref={measureRef} aria-hidden className="pointer-events-none invisible absolute left-0 top-0 flex gap-[26px]">
        {stats.map((s) => <StatCell key={s.label} s={s} />)}
      </div>
      {stats.slice(0, shown).map((s) => <StatCell key={s.label} s={s} />)}
      {hidden.length > 0 && (
        <div className="relative flex-none">
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
            onBlur={() => setOpen(false)}
            className="h-7 rounded-md border border-tm-line2 px-2 text-2xs text-tm-soft hover:bg-tm-raised"
          >
            +{hidden.length} 지표
          </button>
          {open && (
            <div className="fixed z-50 mt-2 flex flex-col gap-2.5 rounded-[10px] border border-tm-line2 bg-tm-panel p-3 shadow-glow-line">
              {hidden.map((s) => <StatCell key={s.label} s={s} />)}
            </div>
          )}
        </div>
      )}
    </div>
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
      <Link href="/login" className="inline-flex h-9 flex-none items-center whitespace-nowrap rounded-lg bg-dracula-purple px-3.5 text-13 font-bold text-tm-on-accent">
        로그인
      </Link>
    );
  }
  return (
    <div className="relative flex-none" ref={ref}>
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
  /** 실전(live)이면 상태 알약·상단 줄이 주황 */
  kind: "paper" | "live";
}

/** ADR-112 — 한 줄 56px. 로고 · 검색 · 제목(또는 종목) · 지표 · (오른쪽 끝) 레이아웃 · 상태 알약 · 알림 · 계정 */
function TopBar({ left, stats, account, searchRef }: { left: ReactNode; stats: TopStat[]; account: AccountChip; searchRef: RefObject<HeaderSearchHandle | null> }) {
  return (
    <header className="flex h-14 flex-none flex-nowrap items-center gap-5 border-b border-tm-line bg-tm-page pl-[15px] pr-4">
      <Link href="/" aria-label="monticker 홈" className="grid w-[26px] flex-none place-items-center">
        <LogoMark />
      </Link>
      <HeaderSearch ref={searchRef} />
      {left}
      <TopStats stats={stats} />
      <div className="ml-auto flex flex-none items-center gap-1.5">
        {/* 레이아웃 편집 — 시안 요소, 기능은 docs/design-rollout-plan.md */}
        <IconBtn name="layout" label="레이아웃 편집 (준비 중)" size={36} iconSize={18} aria-disabled="true" />
        <StatusPill kind={account.kind} />
        <Link href="/alerts" aria-label="알림" className="grid h-9 w-9 flex-none place-items-center rounded-lg text-tm-muted hover:bg-tm-raised hover:text-dracula-fg">
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

/** ⌘K / Ctrl+K → 상단 검색창에 포커스(ADR-112). 검색 화면으로 옮겨 가지 않는다 */
function useSearchShortcut(searchRef: RefObject<HeaderSearchHandle | null>) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        searchRef.current?.focus();
      }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [searchRef]);
}

/**
 * 터미널 화면 골격 — 상단 바(로고·검색·제목/종목·지표·상태 알약·알림·계정) + 왼쪽 라벨 레일 + 섹션 탭 + 본문.
 * 모든 앱 화면은 이걸로 감싼다. 로그인·약관 같은 바깥 화면은 쓰지 않는다. 실전 화면은 상단에 주황 줄을 두른다.
 */
export function TerminalPage({
  title, crumb, left, stats = [], account = { kind: "paper" }, children, className,
}: {
  title?: ReactNode;
  crumb?: ReactNode;
  /** title 대신 왼쪽에 둘 요소(SymbolPill) */
  left?: ReactNode;
  stats?: TopStat[];
  account?: AccountChip;
  children: ReactNode;
  className?: string;
}) {
  const searchRef = useRef<HeaderSearchHandle | null>(null);
  useSearchShortcut(searchRef);
  useConsentGate();
  return (
    <div className="flex min-h-screen flex-col bg-tm-page text-13 text-dracula-fg">
      {account.kind === "live" && <div aria-hidden className="h-[3px] flex-none bg-live" />}
      <TopBar left={left ?? <TitleBlock title={title} crumb={crumb} />} stats={stats} account={account} searchRef={searchRef} />
      <div className="flex min-h-0 flex-1">
        <Rail />
        <div className="flex min-w-0 flex-auto flex-col">
          <SectionTabs />
          <main className={cn("flex min-w-0 flex-auto flex-col gap-2 p-2", className)}>{children}</main>
        </div>
      </div>
    </div>
  );
}
