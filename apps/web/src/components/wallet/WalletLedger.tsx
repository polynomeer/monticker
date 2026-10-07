"use client";

import { useEffect, useRef } from "react";
import { useWalletLedger, type LedgerEvent } from "@/hooks/useWalletLedger";
import { fmtNum } from "@/components/terminal";
import { Skeleton } from "@/components/portfolio/PaperStates";
import { fmtMonthDay, fmtTime } from "@/components/portfolio/format";
import { originLabel } from "@/components/wallet/origin";

export type LedgerFilter = "all" | "fill" | "settle" | "cash";

/** 원장 이벤트 → 표시 문구·색, 그리고 돈이 어디서 어디로 움직였는지(확실한 것만). */
const EVENT_META: Record<string, { label: string; color: string; flow?: string; group: Exclude<LedgerFilter, "all"> }> = {
  DEPOSIT:                   { label: "입금",           color: "text-dracula-green",  flow: "외부 → 현금",       group: "cash" },
  WITHDRAWAL:                { label: "출금",           color: "text-[#ff8a8a]",      flow: "현금 → 외부",       group: "cash" },
  FILL:                      { label: "체결",           color: "text-dracula-cyan",                              group: "fill" },
  PARTIAL_FILL:              { label: "부분체결",       color: "text-dracula-orange",                            group: "fill" },
  CASH_RESERVED:             { label: "예약",           color: "text-dracula-yellow", flow: "현금 → 예약금",     group: "fill" },
  CASH_UNRESERVED:           { label: "예약해제",       color: "text-dracula-yellow", flow: "예약금 → 현금",     group: "fill" },
  FEE:                       { label: "수수료",         color: "text-[#ff8a8a]",                                 group: "fill" },
  SETTLEMENT:                { label: "정산완료",       color: "text-dracula-green",                             group: "settle" },
  PAPER_SETTLEMENT_COMPLETE: { label: "모의투자 정산",  color: "text-dracula-green",  flow: "정산 대기 → 현금",  group: "settle" },
  SUBSCRIPTION_PAYMENT:      { label: "구독 결제",      color: "text-[#ff8a8a]",      flow: "현금 → 외부",       group: "cash" },
  CREATOR_EARNING_CREDITED:  { label: "전략 수익 적립", color: "text-dracula-green",  flow: "외부 → 현금",       group: "cash" },
  CREATOR_PAYOUT_PAID:       { label: "수익 출금",      color: "text-[#ff8a8a]",      flow: "현금 → 외부",       group: "cash" },
  BROKERAGE_SETTLEMENT:      { label: "증권사 정산",    color: "text-dracula-cyan",                              group: "settle" },
};

function isToday(iso: string) {
  const d = new Date(iso);
  const n = new Date();
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

function LedgerRow({ ev }: { ev: LedgerEvent }) {
  const meta = EVENT_META[ev.eventType] ?? { label: ev.eventType, color: "text-dracula-fg" };
  const sign = ev.amount > 0 ? "+" : ev.amount < 0 ? "-" : "";
  return (
    <li className="grid grid-cols-[74px_minmax(0,1fr)_auto] items-center gap-3 border-b border-tm-line px-1 py-[11px]">
      <span className="num text-2xs text-tm-muted">{isToday(ev.createdAt) ? fmtTime(ev.createdAt) : fmtMonthDay(ev.createdAt)}</span>
      <div className="flex min-w-0 flex-col gap-[3px]">
        <span className="truncate text-13 font-semibold">
          <span className={meta.color}>{meta.label}</span>
          {ev.description && <span aria-hidden> — </span>}
          {ev.description && <span>{ev.description}</span>}
        </span>
        <span className="text-xs text-tm-muted">
          {meta.flow ? <span className={meta.color}>{meta.flow}</span> : <span>{ev.paperTradeId ? `거래 #${ev.paperTradeId}` : "원장 기록"}</span>}
          {/* ADR-085 — 체결을 만든 주문의 출처. 체결 행인데 출처를 모르면 "—" */}
          {ev.paperTradeId != null && <span> · 출처 {originLabel(ev.origin, ev.originRef) ?? "—"}</span>}
          {ev.balanceAfter != null && <span className="num"> · 잔고 {fmtNum(ev.balanceAfter)}</span>}
        </span>
      </div>
      <span className="num text-13 font-semibold">{sign}{fmtNum(Math.abs(ev.amount))}</span>
    </li>
  );
}

/** ADR-043 — 원장 타임라인. 커서 페이징 + 바닥 센티널이 보이면 다음 페이지. */
export default function WalletLedger({ filter = "all" }: { filter?: LedgerFilter }) {
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
    <div className="flex flex-col gap-2">
      {[1, 2, 3].map(i => <Skeleton key={i} className="h-12" />)}
    </div>
  );

  if (isError) return <p role="alert" className="m-0 py-12 text-center text-13 text-[#ff8a8a]">원장을 불러오지 못했습니다.</p>;

  const all = pages?.flatMap(p => p.items) ?? [];
  if (all.length === 0) return (
    <p className="m-0 rounded-[10px] border border-dashed border-tm-line2 py-12 text-center text-13 text-tm-muted">
      아직 거래 기록이 없습니다. 모의투자를 시작해보세요.
    </p>
  );
  const items = filter === "all" ? all : all.filter(ev => EVENT_META[ev.eventType]?.group === filter);

  return (
    <div className="flex flex-col">
      {items.length === 0 ? (
        <p className="m-0 py-10 text-center text-13 text-tm-muted">이 구분의 기록이 아직 없습니다.</p>
      ) : (
        <ul className="m-0 list-none p-0">
          {items.map(ev => <LedgerRow key={ev.id} ev={ev} />)}
        </ul>
      )}
      <div ref={sentinelRef} aria-hidden className="h-1" />
      {isFetchingNextPage && <p className="m-0 py-2 text-center text-xs text-tm-muted">불러오는 중…</p>}
      {!hasNextPage && all.length >= 20 && <p className="m-0 py-2 text-center text-xs text-tm-muted">원장의 끝입니다</p>}
    </div>
  );
}
