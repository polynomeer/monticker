"use client";

import { cn } from "@/lib/utils";
import { fmtSigned } from "@/components/terminal";
import type { StockMeta } from "@/components/portfolio/useStockMeta";

export interface PaperSettlement {
  id: number;
  tradeId: number;
  stockId?: number;
  side: "BUY" | "SELL";
  quantity: number;
  fillPrice: number;
  grossAmount: number;
  fee: number;
  tax: number;
  netAmount: number;
  status: "PENDING" | "SETTLED" | "FAILED";
  settleDate: string;
  settledAt: string | null;
  createdAt: string;
}

const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];

export function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** 오늘부터 영업일(주말 제외) n개 — 공휴일 달력은 아직 없어 주말만 건너뛴다 */
export function nextBusinessDays(n = 3, from = new Date()) {
  const out: Date[] = [];
  const d = new Date(from.getFullYear(), from.getMonth(), from.getDate());
  while (out.length < n) {
    if (d.getDay() !== 0 && d.getDay() !== 6) out.push(new Date(d));
    d.setDate(d.getDate() + 1);
  }
  return out;
}

/** 매수는 현금이 나가고(-) 매도는 들어온다(+) */
export const signedNet = (s: PaperSettlement) => (s.side === "BUY" ? -s.netAmount : s.netAmount);

const COLS = [
  { tag: "D", sub: "오늘 현금 반영", dot: "bg-dracula-purple" },
  { tag: "D+1", sub: "내일 현금 반영 예정", dot: "bg-dracula-yellow" },
  { tag: "D+2", sub: "모레 현금 반영 예정", dot: "bg-dracula-cyan" },
];

/** 시안 Settlement "정산 캘린더" — 정산 대기 건을 정산일(오늘·내일·모레 영업일)별로 묶는다. */
export function SettlementCalendar({ pending, meta }: { pending: PaperSettlement[]; meta: Map<number, StockMeta> }) {
  const days = nextBusinessDays(3);
  return (
    <div className="flex flex-wrap gap-2.5">
      {days.map((d, i) => {
        const key = ymd(d);
        const items = pending.filter((s) => s.settleDate.slice(0, 10) === key);
        const c = COLS[i];
        return (
          <div key={key} className="flex min-w-0 flex-[1_1_220px] flex-col gap-2.5 rounded-[10px] bg-tm-inner p-3.5">
            <div className="flex items-center justify-between">
              <span className="font-bold">
                {c.tag} · {String(d.getMonth() + 1).padStart(2, "0")}.{String(d.getDate()).padStart(2, "0")} ({WEEKDAY[d.getDay()]})
              </span>
              <span className={cn("h-2 w-2 rounded-full", c.dot)} />
            </div>
            <span className="text-xs text-tm-muted">{c.sub}</span>
            {items.length === 0 ? (
              <span className="rounded-lg border border-dashed border-tm-line2 px-2.5 py-[9px] text-center text-xs text-tm-muted">정산 예정 없음</span>
            ) : (
              items.map((s) => {
                const name = (s.stockId && meta.get(s.stockId)?.name) || `거래 #${s.tradeId}`;
                const v = signedNet(s);
                return (
                  <div key={s.id} className="flex justify-between rounded-lg bg-tm-panel px-2.5 py-[9px] text-13">
                    <span>{name} {s.side === "BUY" ? "매수" : "매도"}</span>
                    <span className={cn("num", v < 0 ? "text-down" : "text-up")}>{fmtSigned(v)}</span>
                  </div>
                );
              })
            )}
          </div>
        );
      })}
    </div>
  );
}
