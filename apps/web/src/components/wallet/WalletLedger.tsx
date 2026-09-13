"use client";

import { useEffect, useRef } from "react";
import {
  type Icon, ArrowLineDown, ArrowLineUp, CheckCircle, CircleHalf, Lock, LockOpen,
  Receipt, Target, ClipboardText, CreditCard, Coins, Bank, Buildings, Circle,
} from "@phosphor-icons/react";
import { Card } from "@/components/ui/Card";
import { useWalletLedger, type LedgerEvent } from "@/hooks/useWalletLedger";

const EVENT_LABELS: Record<string, { label: string; color: string; icon: Icon }> = {
  DEPOSIT:                    { label: "입금",           color: "text-dracula-green", icon: ArrowLineDown },
  WITHDRAWAL:                 { label: "출금",           color: "text-dracula-red", icon: ArrowLineUp },
  FILL:                       { label: "체결",           color: "text-dracula-purple", icon: CheckCircle },
  PARTIAL_FILL:               { label: "부분체결",       color: "text-dracula-orange", icon: CircleHalf },
  CASH_RESERVED:              { label: "예약",           color: "text-gray-500 dark:text-dracula-comment", icon: Lock },
  CASH_UNRESERVED:            { label: "예약해제",       color: "text-gray-500 dark:text-dracula-comment", icon: LockOpen },
  FEE:                        { label: "수수료",         color: "text-dracula-red", icon: Receipt },
  SETTLEMENT:                 { label: "정산완료",       color: "text-dracula-green", icon: Target },
  PAPER_SETTLEMENT_COMPLETE:  { label: "모의투자 정산",  color: "text-dracula-green", icon: ClipboardText },
  SUBSCRIPTION_PAYMENT:       { label: "구독 결제",      color: "text-dracula-red", icon: CreditCard },
  CREATOR_EARNING_CREDITED:   { label: "전략 수익 적립", color: "text-dracula-green", icon: Coins },
  CREATOR_PAYOUT_PAID:        { label: "수익 출금",      color: "text-dracula-red", icon: Bank },
  BROKERAGE_SETTLEMENT:       { label: "증권사 정산",    color: "text-dracula-cyan", icon: Buildings },
};

function won(n: number) {
  return n.toLocaleString("ko-KR") + "원";
}

function LedgerRow({ ev }: { ev: LedgerEvent }) {
  const meta = EVENT_LABELS[ev.eventType] ?? { label: ev.eventType, color: "text-gray-900 dark:text-dracula-fg", icon: Circle };
  const sign = ev.amount > 0 ? "+" : "";
  return (
    <Card className="flex items-center gap-3 p-3">
      <meta.icon size={20} weight="bold" className={meta.color} aria-hidden />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className={`text-xs font-medium ${meta.color}`}>{meta.label}</span>
          {ev.description && <span className="text-xs text-gray-500 dark:text-dracula-comment truncate">{ev.description}</span>}
        </div>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          {new Date(ev.createdAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
        </p>
      </div>
      <div className="text-right shrink-0">
        <p className={`text-sm font-semibold ${ev.amount >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
          {sign}{won(Math.abs(ev.amount))}
        </p>
        {ev.balanceAfter != null && (
          <p className="text-xs text-gray-500 dark:text-dracula-comment">잔고 {won(ev.balanceAfter)}</p>
        )}
      </div>
    </Card>
  );
}

/** ADR-043 — 원장 타임라인. 커서 페이징 + 바닥 센티널이 보이면 다음 페이지. */
export default function WalletLedger() {
  const { data: pages, isLoading, isError, hasNextPage, isFetchingNextPage, fetchNextPage } = useWalletLedger();
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const node = sentinelRef.current;
    if (!node || !hasNextPage) return;
    const obs = new IntersectionObserver(
      ([entry]) => { if (entry.isIntersecting && !isFetchingNextPage) fetchNextPage(); },
      { rootMargin: "200px" },   // 바닥에 닿기 전에 미리 받아 스크롤이 끊기지 않게
    );
    obs.observe(node);
    return () => obs.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  if (isLoading) return (
    <div className="space-y-2">
      {[1, 2, 3].map(i => <div key={i} className="h-16 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}
    </div>
  );

  if (isError) return (
    <div className="text-center py-12 text-dracula-red text-sm">원장을 불러오지 못했습니다.</div>
  );

  const items = pages?.flatMap(p => p.items) ?? [];
  if (items.length === 0) return (
    <div className="text-center py-12 text-gray-500 dark:text-dracula-comment text-sm border border-dashed border-gray-300 dark:border-dracula-line rounded-xl">
      아직 거래 기록이 없습니다. 모의투자를 시작해보세요.
    </div>
  );

  return (
    <div className="space-y-2">
      {items.map(ev => <LedgerRow key={ev.id} ev={ev} />)}
      <div ref={sentinelRef} aria-hidden className="h-1" />
      {isFetchingNextPage && <p className="text-center text-xs text-gray-500 dark:text-dracula-comment py-2">불러오는 중…</p>}
      {!hasNextPage && items.length >= 20 && <p className="text-center text-xs text-gray-400 dark:text-dracula-comment py-2">원장의 끝입니다</p>}
    </div>
  );
}
