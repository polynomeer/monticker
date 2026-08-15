"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { type Icon, ArrowLeft, ArrowLineDown, ArrowLineUp, Money, Bank, Receipt, Circle } from "@phosphor-icons/react";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";

interface ReplayEvent {
  time: string;
  type: string;
  stockSymbol: string | null;
  stockName: string | null;
  quantity: number | null;
  price: number | null;
  amount: number | null;
  pnlPct: number | null;
  description: string | null;
}

interface DailyReplay {
  date: string;
  events: ReplayEvent[];
  summary: {
    totalPnl: number;
    tradeCount: number;
    bestTrade: string | null;
    worstTrade: string | null;
  };
}

const TYPE_META: Record<string, { icon: Icon; color: string; label: string }> = {
  BUY:        { icon: ArrowLineDown, color: "text-dracula-green", label: "매수" },
  SELL:       { icon: ArrowLineUp, color: "text-dracula-red", label: "매도" },
  DEPOSIT:    { icon: Money, color: "text-dracula-purple", label: "입금" },
  WITHDRAWAL: { icon: Bank, color: "text-gray-500 dark:text-dracula-comment", label: "출금" },
  FEE:        { icon: Receipt, color: "text-gray-500 dark:text-dracula-comment", label: "수수료" },
};

function won(n: number) { return n.toLocaleString("ko-KR") + "원"; }

export default function ReplayPage() {
  const router = useRouter();
  const [date, setDate] = useState(() => new Date().toISOString().split("T")[0]);

  const { data, isLoading, error } = useQuery<DailyReplay>({
    queryKey: ["wallet", "replay", date],
    queryFn: async () => {
      const res = await authFetch(`/api/wallet/replay?date=${date}`);
      if (!res.ok) throw new Error("리플레이 조회 실패");
      return res.json();
    },
  });

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="flex items-center gap-3 mb-6">
        <button
          onClick={() => router.back()}
          aria-label="뒤로가기"
          className="inline-flex items-center justify-center w-8 h-8 -ml-1 text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg text-sm transition-colors active:scale-95"
        ><ArrowLeft size={18} weight="bold" aria-hidden /></button>
        <h1 className="text-xl font-bold text-gray-900 dark:text-dracula-fg">주문 리플레이</h1>
      </div>

      {/* 날짜 선택 */}
      <div className="mb-6 flex items-center gap-3">
        <input type="date" value={date} onChange={e => setDate(e.target.value)}
          className="rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-sm transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
        />
        <span className="text-xs text-gray-500 dark:text-dracula-comment">선택한 날짜의 투자 기록을 복기합니다</span>
      </div>

      {isLoading && (
        <div className="space-y-2">{[1,2,3].map(i => <div key={i} className="h-14 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}</div>
      )}

      {error && <p className="text-dracula-red text-sm text-center py-8">데이터를 불러올 수 없습니다.</p>}

      {data && (
        <>
          {/* 일일 요약 */}
          {data.events.length > 0 && (
            <Card className="grid grid-cols-2 gap-3 p-4" outerClassName="mb-6">
              <div>
                <p className="text-xs text-gray-500 dark:text-dracula-comment">총 손익</p>
                <p className={`text-lg font-bold ${data.summary.totalPnl >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
                  {data.summary.totalPnl >= 0 ? "+" : ""}{won(data.summary.totalPnl)}
                </p>
              </div>
              <div>
                <p className="text-xs text-gray-500 dark:text-dracula-comment">거래 횟수</p>
                <p className="text-lg font-bold text-gray-900 dark:text-dracula-fg">{data.summary.tradeCount}회</p>
              </div>
              {data.summary.bestTrade && (
                <div className="col-span-2">
                  <p className="text-xs text-gray-500 dark:text-dracula-comment">최고 거래</p>
                  <p className="text-sm text-dracula-green">{data.summary.bestTrade}</p>
                </div>
              )}
            </Card>
          )}

          {/* 이벤트 타임라인 */}
          {data.events.length === 0 ? (
            <div className="text-center py-16 border border-dashed border-gray-300 dark:border-dracula-line rounded-xl text-gray-500 dark:text-dracula-comment text-sm">
              이날의 거래 기록이 없습니다.
            </div>
          ) : (
            <div className="relative pl-6">
              {/* 세로선 */}
              <div className="absolute left-2 top-0 bottom-0 w-px bg-gray-200 dark:bg-dracula-line" />
              <div className="space-y-4">
                {data.events.map((ev: ReplayEvent, i: number) => {
                  const meta = TYPE_META[ev.type] ?? { icon: Circle, color: "text-gray-900 dark:text-dracula-fg", label: ev.type };
                  return (
                    <div key={i} className="relative">
                      {/* 점 */}
                      <div className="absolute -left-[18px] top-3 w-3 h-3 rounded-full bg-gray-300 dark:bg-dracula-line border-2 border-white dark:border-dracula-bg" />
                      <Card className="p-3">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <meta.icon size={14} weight="bold" className={meta.color} aria-hidden />
                            <span className={`text-xs font-medium ${meta.color}`}>{meta.label}</span>
                            {ev.stockSymbol && (
                              <span className="text-xs text-gray-900 dark:text-dracula-fg font-semibold">{ev.stockName ?? ev.stockSymbol}</span>
                            )}
                            {ev.quantity && <span className="text-xs text-gray-500 dark:text-dracula-comment">{ev.quantity}주</span>}
                          </div>
                          <span className="text-xs text-gray-500 dark:text-dracula-comment">
                            {new Date(ev.time).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}
                          </span>
                        </div>
                        <div className="mt-1 flex items-center justify-between">
                          {ev.price && <span className="text-xs text-gray-500 dark:text-dracula-comment">@{ev.price.toLocaleString()}원</span>}
                          {ev.amount != null && (
                            <span className={`text-sm font-semibold ${ev.amount >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
                              {ev.amount >= 0 ? "+" : ""}{won(Math.abs(ev.amount))}
                            </span>
                          )}
                          {ev.pnlPct != null && (
                            <span className={`text-xs font-medium ${ev.pnlPct >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
                              {ev.pnlPct >= 0 ? "+" : ""}{ev.pnlPct.toFixed(2)}%
                            </span>
                          )}
                        </div>
                        {ev.description && <p className="text-xs text-gray-500 dark:text-dracula-comment mt-1">{ev.description}</p>}
                      </Card>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
