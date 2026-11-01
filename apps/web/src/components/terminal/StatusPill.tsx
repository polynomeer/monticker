"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/useAuth";
import { useAccountChipSummary } from "@/hooks/useAccountSummary";
import { useStatusSummary } from "@/hooks/useStatusSummary";
import { Icon } from "./Icon";

const BROKER_NAME: Record<string, string> = { KIS: "한국투자증권", TOSS: "토스증권" };

/**
 * ADR-112 — 계좌 칩을 대신하는 상태 알약. 잔액을 늘 띄우지 않고 모드 배지 + 지금 진행 중인 것(실행 중 전략·미체결)만 보인다.
 * 잔액은 펼침 메뉴에서 본다. 실전 화면은 배지·테두리가 주황이다(주황 = 실제 돈).
 */
export function StatusPill({ kind }: { kind: "paper" | "live" }) {
  const live = kind === "live";
  const { isLoggedIn } = useAuth();
  const summary = useStatusSummary(kind, isLoggedIn);
  const balance = useAccountChipSummary(kind, isLoggedIn);
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

  const items: string[] = [];
  if (live && summary.brokerLinked) items.push(`${BROKER_NAME[summary.brokerProvider ?? ""] ?? "증권사"} 연동`);
  if (summary.runningStrategies) items.push(`전략 ${summary.runningStrategies} 실행 중`);
  if (summary.openOrders) items.push(`미체결 ${summary.openOrders}`);
  const label = [
    live ? "실전 모드" : "모의투자 모드",
    ...items,
    isLoggedIn && items.length === 0 ? "진행 중인 것 없음" : null,
  ].filter(Boolean).join(", ");

  return (
    <div className="relative flex-none" ref={ref}>
      <button
        type="button"
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={`${label}. 계좌 요약 열기`}
        data-account={kind}
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex h-9 items-center gap-2 whitespace-nowrap rounded-full border px-2.5 text-xs text-dracula-fg hover:bg-tm-raised",
          live ? "border-live/60" : "border-tm-line2",
        )}
      >
        <span className={cn("flex items-center gap-1 rounded-full px-2 py-px font-bold", live ? "bg-live/15 text-live" : "bg-caution/15 text-caution")}>
          <span aria-hidden className={cn("h-1.5 w-1.5 rounded-full", live ? "bg-live" : "bg-caution")} />
          {live ? "실전" : "모의"}
        </span>
        {items.map((t, i) => (
          <span key={t} className="flex items-center gap-1 text-tm-soft">
            {t.startsWith("전략") && <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-dracula-green" />}
            {i > 0 && <span aria-hidden className="text-tm-muted">·</span>}
            {t}
          </span>
        ))}
        <Icon name="chev" size={14} className="text-tm-muted" />
      </button>
      {open && (
        <div role="menu" className="absolute right-0 top-full z-50 mt-2 w-60 overflow-hidden rounded-[10px] border border-tm-line2 bg-tm-panel py-1.5 shadow-glow-line">
          {balance.amount !== undefined && (
            <div className="flex items-baseline justify-between px-4 py-2">
              <span className="text-2xs text-tm-muted">{balance.amountLabel}</span>
              <span className={cn("num text-13", live ? "text-live" : "text-dracula-fg")}>{balance.amount}</span>
            </div>
          )}
          <div className="px-4 pb-1.5 text-2xs text-tm-muted">
            {items.length > 0 ? items.join(" · ") : isLoggedIn ? "진행 중인 전략·주문이 없어요" : "로그인하면 계좌 요약이 보여요"}
          </div>
          <div className="my-1 h-px bg-tm-line" />
          <Link role="menuitem" href="/assets" className="flex items-center gap-2.5 px-4 py-2 text-13 text-dracula-fg hover:bg-tm-raised">
            <Icon name="pie" size={16} /> 내 자산
          </Link>
          <Link role="menuitem" href="/brokerage" className="flex items-center gap-2.5 px-4 py-2 text-13 text-dracula-fg hover:bg-tm-raised">
            <Icon name="bank" size={16} /> 실전투자
          </Link>
        </div>
      )}
    </div>
  );
}
