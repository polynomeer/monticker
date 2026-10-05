// 터미널 디자인 시안의 기본 요소들. 시안 캔버스의 lib.py 헬퍼(btn, seg, field, chip…)와
// 1:1로 대응한다 — 화면을 만들 때는 새 스타일을 지어내지 말고 여기 있는 것을 조합한다.
"use client";

import Link from "next/link";
import type { ComponentProps, ReactNode } from "react";
import { cn } from "@/lib/utils";
import { Icon, type IconName } from "./Icon";

// ── 버튼 ───────────────────────────────────────────────────────────────
export type BtnKind = "primary" | "buy" | "sell" | "ghost" | "soft" | "danger" | "warn";

const BTN_KIND: Record<BtnKind, string> = {
  primary: "bg-dracula-purple text-tm-page font-bold hover:brightness-110",
  buy: "bg-up text-tm-page font-bold hover:brightness-110",
  sell: "bg-down text-tm-page font-bold hover:brightness-110",
  ghost: "border border-tm-line2 text-dracula-fg font-semibold hover:bg-tm-raised",
  soft: "bg-tm-raised text-dracula-fg font-semibold hover:bg-tm-line2",
  danger: "border border-[#6b3a44] text-[#ff8a8a] font-semibold hover:bg-[#3d252b]",
  warn: "bg-dracula-orange text-tm-page font-bold hover:brightness-110",
};

const BTN_SIZE = { sm: "h-7 px-2.5 text-xs", md: "h-10 px-4 text-sm", lg: "h-11 px-4 text-sm", xl: "h-12 px-5 text-15" };

interface BtnBase {
  kind?: BtnKind;
  size?: keyof typeof BTN_SIZE;
  icon?: IconName;
  full?: boolean;
  className?: string;
  children: ReactNode;
}

function btnClass({ kind = "primary", size = "md", full, className }: Omit<BtnBase, "children" | "icon">) {
  return cn(
    "inline-flex items-center justify-center gap-2 rounded-lg whitespace-nowrap transition-[filter,background-color] disabled:opacity-50 disabled:cursor-not-allowed",
    BTN_KIND[kind],
    BTN_SIZE[size],
    full && "w-full",
    className,
  );
}

export function Btn({ kind, size, icon, full, className, children, type = "button", ...rest }: BtnBase & Omit<ComponentProps<"button">, "children">) {
  return (
    <button type={type} className={btnClass({ kind, size, full, className })} {...rest}>
      {icon && <Icon name={icon} size={16} />}
      <span>{children}</span>
    </button>
  );
}

export function BtnLink({ kind, size, icon, full, className, children, href }: BtnBase & { href: string }) {
  return (
    <Link href={href} className={btnClass({ kind, size, full, className })}>
      {icon && <Icon name={icon} size={16} />}
      <span>{children}</span>
    </Link>
  );
}

export function IconBtn({ name, label, size = 32, iconSize = 16, className, ...rest }: { name: IconName; label: string; size?: number; iconSize?: number } & Omit<ComponentProps<"button">, "children">) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      style={{ width: size, height: size }}
      className={cn("grid flex-none place-items-center rounded-lg text-tm-muted hover:bg-tm-raised hover:text-dracula-fg", className)}
      {...rest}
    >
      <Icon name={name} size={iconSize} />
    </button>
  );
}

// ── 세그먼트 · 매수/매도 ──────────────────────────────────────────────
export function Seg<T extends string>({
  options, value, onChange, full, size = "md", className,
}: {
  options: readonly { value: T; label: ReactNode }[];
  value: T;
  onChange?: (v: T) => void;
  full?: boolean;
  size?: "sm" | "md" | "lg";
  className?: string;
}) {
  const h = { sm: "h-[26px]", md: "h-7", lg: "h-[30px]" }[size];
  return (
    <div className={cn(full ? "flex" : "inline-flex", "gap-0.5 rounded-lg bg-tm-inner p-[3px]", className)}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            aria-pressed={on}
            onClick={() => onChange?.(o.value)}
            className={cn(
              h, "rounded-md px-3 text-13 whitespace-nowrap",
              full && "flex-1",
              on ? "bg-tm-line2 font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg",
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

export function BuySell({ value, onChange, buyLabel = "매수", sellLabel = "매도" }: { value: "BUY" | "SELL"; onChange?: (v: "BUY" | "SELL") => void; buyLabel?: string; sellLabel?: string }) {
  const base = "h-9 flex-1 rounded-md text-sm";
  return (
    <div className="flex gap-0.5 rounded-lg bg-tm-inner p-[3px]">
      <button type="button" aria-pressed={value === "BUY"} onClick={() => onChange?.("BUY")} className={cn(base, value === "BUY" ? "bg-up font-bold text-tm-page" : "text-tm-muted")}>{buyLabel}</button>
      <button type="button" aria-pressed={value === "SELL"} onClick={() => onChange?.("SELL")} className={cn(base, value === "SELL" ? "bg-down font-bold text-tm-page" : "text-tm-muted")}>{sellLabel}</button>
    </div>
  );
}

// ── 입력 ───────────────────────────────────────────────────────────────
/** 라벨이 박스 안 위쪽에 붙는 촘촘한 입력(주문폼 스타일). */
export function Field({ label, unit, className, inputClassName, mono = true, ...rest }: { label: string; unit?: string; mono?: boolean; inputClassName?: string } & ComponentProps<"input">) {
  return (
    <label className={cn("flex min-h-[52px] min-w-0 flex-1 items-center gap-2 rounded-lg border border-tm-line bg-tm-inner px-3 py-1.5", className)}>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-2xs text-tm-muted">{label}</span>
        <input className={cn("w-full bg-transparent p-0 text-sm text-dracula-fg outline-none placeholder:text-[#8b92b8]", mono && "num", inputClassName)} {...rest} />
      </span>
      {unit && <span className="text-xs text-tm-muted">{unit}</span>}
    </label>
  );
}

/** 라벨이 박스 바깥 위에 붙는 넓은 입력(로그인·연동 폼 스타일). */
export function TextField({ label, icon, hint, error, className, ...rest }: { label: string; icon?: IconName; hint?: ReactNode; error?: ReactNode } & ComponentProps<"input">) {
  return (
    <label className={cn("flex flex-col gap-1.5 text-13 text-tm-soft", className)}>
      {label}
      <span className={cn("flex h-[46px] items-center gap-2.5 rounded-[10px] border bg-tm-inner px-3.5 focus-within:border-dracula-purple", error ? "border-[#ff8a8a]" : "border-tm-line2")}>
        {icon && <Icon name={icon} size={16} className="text-tm-muted" />}
        <input className="min-w-0 flex-1 bg-transparent text-15 text-dracula-fg outline-none placeholder:text-[#8b92b8]" {...rest} />
      </span>
      {error ? <span className="text-xs text-[#ff8a8a]">{error}</span> : hint ? <span className="text-xs text-tm-muted">{hint}</span> : null}
    </label>
  );
}

/** 네이티브 select를 시안의 드롭다운 박스 모양으로 감싼다. */
export function SelectBox({ label, className, children, ...rest }: { label?: string } & ComponentProps<"select">) {
  return (
    <label className={cn("relative flex min-h-10 w-full flex-col justify-center gap-0.5 rounded-lg border border-tm-line bg-tm-inner px-3 py-1.5", className)}>
      {label && <span className="text-2xs text-tm-muted">{label}</span>}
      <select className="w-full appearance-none bg-transparent pr-6 text-sm text-dracula-fg outline-none [&>option]:bg-tm-panel" {...rest}>
        {children}
      </select>
      <Icon name="chev" size={16} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-tm-muted" />
    </label>
  );
}

export function Toggle({ checked, onChange, label, disabled }: { checked: boolean; onChange?: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange?.(!checked)}
      className={cn("flex h-[26px] w-11 flex-none rounded-full p-[3px] disabled:opacity-50", checked ? "bg-dracula-purple" : "bg-tm-line2")}
    >
      <span className={cn("h-5 w-5 rounded-full", checked ? "ml-auto bg-dracula-fg" : "bg-tm-muted")} />
    </button>
  );
}

export function Checkbox({ checked, onChange, label, sub, disabled }: { checked: boolean; onChange?: (v: boolean) => void; label: ReactNode; sub?: ReactNode; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange?.(!checked)}
      className="flex items-start gap-2.5 text-left text-13 text-dracula-fg disabled:opacity-50"
    >
      <span className={cn("grid h-[18px] w-[18px] flex-none place-items-center rounded-[5px]", checked ? "bg-dracula-purple text-tm-page" : "border-[1.5px] border-tm-line2 text-transparent")}>
        <Icon name="check" size={13} strokeWidth={3} />
      </span>
      <span className="flex flex-col gap-0.5">
        <span>{label}</span>
        {sub && <span className="text-xs text-tm-muted">{sub}</span>}
      </span>
    </button>
  );
}

// ── 칩 · 배지 ──────────────────────────────────────────────────────────
export function Chip({ children, dot, active, onClick, className }: { children: ReactNode; dot?: string; active?: boolean; onClick?: () => void; className?: string }) {
  const cls = cn(
    "inline-flex h-[26px] items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 text-xs font-medium",
    active ? "border border-dracula-purple bg-[#3a2f52] text-dracula-fg" : "bg-tm-raised text-tm-soft",
    onClick && "cursor-pointer hover:text-dracula-fg",
    className,
  );
  const inner = (
    <>
      {dot && <span className="h-[7px] w-[7px] rounded-full" style={{ background: dot }} />}
      {children}
    </>
  );
  return onClick ? <button type="button" aria-pressed={active} onClick={onClick} className={cls}>{inner}</button> : <span className={cls}>{inner}</span>;
}

export type Tone = "green" | "red" | "orange" | "cyan" | "purple" | "yellow" | "muted" | "pink";
const TONE: Record<Tone, string> = {
  green: "bg-[#22392c] text-dracula-green",
  red: "bg-[#47262d] text-[#ff8a8a]",
  orange: "bg-[#43342a] text-dracula-orange",
  cyan: "bg-[#213a44] text-dracula-cyan",
  purple: "bg-[#3a2f52] text-[#e2d0ff]",
  yellow: "bg-[#3d3f2a] text-dracula-yellow",
  muted: "bg-tm-raised text-tm-soft",
  pink: "bg-[#46283c] text-dracula-pink",
};

export function Pill({ children, tone = "muted", className }: { children: ReactNode; tone?: Tone; className?: string }) {
  return <span className={cn("inline-flex h-[22px] items-center whitespace-nowrap rounded-md px-2 text-xs font-semibold", TONE[tone], className)}>{children}</span>;
}

/** 실계좌 화면 표식 — 모의투자 화면과 혼동하지 않도록 실거래 경로에는 반드시 붙인다. */
export function LiveBadge() {
  return (
    <span className="inline-flex h-[26px] items-center gap-1.5 rounded-full bg-[#43342a] px-2.5 text-xs font-bold text-dracula-orange">
      <span className="h-[7px] w-[7px] rounded-full bg-dracula-orange" />
      실계좌
    </span>
  );
}

// ── 숫자 · 통계 ────────────────────────────────────────────────────────
export function Stat({ label, value, sub, className, valueClassName, big }: { label: ReactNode; value: ReactNode; sub?: ReactNode; className?: string; valueClassName?: string; big?: boolean }) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-[3px]", className)}>
      <span className="whitespace-nowrap text-2xs text-tm-muted">{label}</span>
      <span className={cn("num whitespace-nowrap", big ? "text-[1.375rem] font-semibold" : "text-sm font-medium", valueClassName)}>{value}</span>
      {sub && <span className="num text-2xs text-tm-muted">{sub}</span>}
    </div>
  );
}

export function KV({ k, v, valueClassName, mono = true }: { k: ReactNode; v: ReactNode; valueClassName?: string; mono?: boolean }) {
  return (
    <div className="flex justify-between gap-3 text-13">
      <span className="text-tm-muted">{k}</span>
      <span className={cn("text-right", mono && "num", valueClassName)}>{v}</span>
    </div>
  );
}

export function Bar({ pct, color = "bg-dracula-purple", h = 6, track = "bg-tm-inner" }: { pct: number; color?: string; h?: number; track?: string }) {
  return (
    <div className={cn("overflow-hidden rounded-full", track)} style={{ height: h }}>
      <div className={cn("h-full rounded-full", color)} style={{ width: `${Math.max(0, Math.min(100, pct))}%` }} />
    </div>
  );
}

/** 등락 방향 색 클래스 — 0 이상은 상승색. */
export function dirClass(v: number | null | undefined) {
  if (v == null || v === 0) return "text-dracula-fg";
  return v > 0 ? "text-up" : "text-down";
}

export function fmtNum(v: number | null | undefined, digits = 0) {
  if (v == null || Number.isNaN(v)) return "—";
  return v.toLocaleString("ko-KR", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function fmtPct(v: number | null | undefined, digits = 2) {
  if (v == null || Number.isNaN(v)) return "—";
  return `${v > 0 ? "+" : ""}${v.toFixed(digits)}%`;
}

export function fmtSigned(v: number | null | undefined, digits = 0) {
  if (v == null || Number.isNaN(v)) return "—";
  return `${v > 0 ? "+" : ""}${fmtNum(v, digits)}`;
}

export function Num({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cn("num", className)}>{children}</span>;
}

export function ChgNum({ value, digits = 2, className }: { value: number | null | undefined; digits?: number; className?: string }) {
  return <span className={cn("num", dirClass(value), className)}>{fmtPct(value, digits)}</span>;
}

// ── 블록 ───────────────────────────────────────────────────────────────
export function H2({ children, sub }: { children: ReactNode; sub?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-3">
      <h2 className="m-0 text-15 font-bold text-dracula-fg">{children}</h2>
      {sub && <span className="text-xs font-normal text-tm-muted">{sub}</span>}
    </div>
  );
}

const NOTICE = {
  info: { box: "bg-[#252d40] border-[#33415e]", icon: "text-dracula-cyan" },
  warn: { box: "bg-[#3a2f26] border-[#5a4430]", icon: "text-dracula-orange" },
  danger: { box: "bg-[#3d252b] border-[#64363f]", icon: "text-[#ff8a8a]" },
  ok: { box: "bg-[#22352a] border-[#2f4d39]", icon: "text-dracula-green" },
};

export function Notice({ tone = "info", icon, children, className }: { tone?: keyof typeof NOTICE; icon?: IconName; children: ReactNode; className?: string }) {
  const t = NOTICE[tone];
  return (
    <div role={tone === "danger" ? "alert" : undefined} className={cn("flex items-start gap-2.5 rounded-[10px] border px-3.5 py-3 text-13 leading-relaxed text-tm-soft", t.box, className)}>
      <Icon name={icon ?? (tone === "info" ? "info" : tone === "ok" ? "check" : "alert")} size={16} className={cn("mt-px flex-none", t.icon)} />
      <div>{children}</div>
    </div>
  );
}

export function Tile({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("flex min-w-0 flex-col gap-2 rounded-[10px] bg-tm-inner p-3.5", className)}>{children}</div>;
}

/** auto-fit 그리드 — 시안의 grid(items, minw) */
export function AutoGrid({ min = 200, gap = 10, children, className }: { min?: number; gap?: number; children: ReactNode; className?: string }) {
  return (
    <div className={cn("grid", className)} style={{ gridTemplateColumns: `repeat(auto-fit,minmax(${min}px,1fr))`, gap }}>
      {children}
    </div>
  );
}

export function Divider() {
  return <div className="h-px bg-tm-line" />;
}

/** 종목 셀 — 이니셜 아바타 + 이름 + 코드 */
export function StockCell({ name, code, href }: { name: string; code?: string; href?: string }) {
  const body = (
    <div className="flex items-center gap-2.5">
      <span className="grid h-7 w-7 flex-none place-items-center rounded-lg bg-tm-raised text-xs font-bold text-tm-soft">{name.slice(0, 1)}</span>
      <div className="flex flex-col gap-px">
        <span className="font-semibold">{name}</span>
        {code && <span className="num text-2xs text-tm-muted">{code}</span>}
      </div>
    </div>
  );
  return href ? <Link href={href} className="text-dracula-fg hover:text-dracula-fg">{body}</Link> : body;
}

export const EVENT_COLOR: Record<string, string> = {
  뉴스: "#8be9fd", 공시: "#ffb86c", 거래량: "#bd93f9", 급등: "#50fa7b", 급락: "#ff79c6", 배당: "#f1fa8c", 시그널: "#50fa7b", 감성: "#ff79c6",
};

export function EventBadge({ type }: { type?: string | null }) {
  if (!type) return <span className="text-tm-muted">—</span>;
  const c = EVENT_COLOR[type] ?? "#c3c8e2";
  return (
    <span className="inline-flex items-center gap-1.5 text-xs" style={{ color: c }}>
      <span className="h-[7px] w-[7px] rounded-full" style={{ background: c }} />
      {type}
    </span>
  );
}

/** 시안에만 있고 아직 백엔드가 없는 기능 — 화면은 그대로 그리되 이 표식으로 구분한다(docs/design-rollout-plan.md). */
export function PreviewTag({ className }: { className?: string }) {
  return <span className={cn("inline-flex h-[18px] items-center rounded px-1.5 text-[0.625rem] font-semibold tracking-wide text-tm-muted ring-1 ring-tm-line2", className)}>준비 중</span>;
}
