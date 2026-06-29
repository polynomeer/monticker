"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { authFetch } from "@/services/api";

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

const TYPE_META: Record<string, { icon: string; color: string; label: string }> = {
  BUY:        { icon: "📥", color: "text-[#50fa7b]", label: "매수" },
  SELL:       { icon: "📤", color: "text-[#ff5555]", label: "매도" },
  DEPOSIT:    { icon: "💵", color: "text-[#bd93f9]", label: "입금" },
  WITHDRAWAL: { icon: "🏧", color: "text-[#6272a4]", label: "출금" },
  FEE:        { icon: "💸", color: "text-[#6272a4]", label: "수수료" },
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
    <div className="max-w-2xl mx-auto px-4 py-8">
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => router.back()} className="text-[#6272a4] hover:text-[#f8f8f2] text-sm">←</button>
        <h1 className="text-xl font-bold text-[#f8f8f2]">주문 리플레이</h1>
      </div>

      {/* 날짜 선택 */}
      <div className="mb-6 flex items-center gap-3">
        <input type="date" value={date} onChange={e => setDate(e.target.value)}
          className="rounded-lg bg-[#282a36] border border-[#44475a] text-[#f8f8f2] px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-[#bd93f9]"
        />
        <span className="text-xs text-[#6272a4]">선택한 날짜의 투자 기록을 복기합니다</span>
      </div>

      {isLoading && (
        <div className="space-y-2">{[1,2,3].map(i => <div key={i} className="h-14 rounded-xl bg-[#44475a]/30 animate-pulse" />)}</div>
      )}

      {error && <p className="text-[#ff5555] text-sm text-center py-8">데이터를 불러올 수 없습니다.</p>}

      {data && (
        <>
          {/* 일일 요약 */}
          {data.events.length > 0 && (
            <div className="mb-6 p-4 rounded-xl border border-[#44475a] bg-[#21222c] grid grid-cols-2 gap-3">
              <div>
                <p className="text-xs text-[#6272a4]">총 손익</p>
                <p className={`text-lg font-bold ${data.summary.totalPnl >= 0 ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                  {data.summary.totalPnl >= 0 ? "+" : ""}{won(data.summary.totalPnl)}
                </p>
              </div>
              <div>
                <p className="text-xs text-[#6272a4]">거래 횟수</p>
                <p className="text-lg font-bold text-[#f8f8f2]">{data.summary.tradeCount}회</p>
              </div>
              {data.summary.bestTrade && (
                <div className="col-span-2">
                  <p className="text-xs text-[#6272a4]">최고 거래</p>
                  <p className="text-sm text-[#50fa7b]">{data.summary.bestTrade}</p>
                </div>
              )}
            </div>
          )}

          {/* 이벤트 타임라인 */}
          {data.events.length === 0 ? (
            <div className="text-center py-16 border border-dashed border-[#44475a] rounded-xl text-[#6272a4] text-sm">
              이날의 거래 기록이 없습니다.
            </div>
          ) : (
            <div className="relative pl-6">
              {/* 세로선 */}
              <div className="absolute left-2 top-0 bottom-0 w-px bg-[#44475a]" />
              <div className="space-y-4">
                {data.events.map((ev: ReplayEvent, i: number) => {
                  const meta = TYPE_META[ev.type] ?? { icon: "•", color: "text-[#f8f8f2]", label: ev.type };
                  return (
                    <div key={i} className="relative">
                      {/* 점 */}
                      <div className="absolute -left-[18px] top-3 w-3 h-3 rounded-full bg-[#44475a] border-2 border-[#282a36]" />
                      <div className="p-3 rounded-xl border border-[#44475a] bg-[#21222c]">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <span>{meta.icon}</span>
                            <span className={`text-xs font-medium ${meta.color}`}>{meta.label}</span>
                            {ev.stockSymbol && (
                              <span className="text-xs text-[#f8f8f2] font-semibold">{ev.stockName ?? ev.stockSymbol}</span>
                            )}
                            {ev.quantity && <span className="text-xs text-[#6272a4]">{ev.quantity}주</span>}
                          </div>
                          <span className="text-xs text-[#6272a4]">
                            {new Date(ev.time).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}
                          </span>
                        </div>
                        <div className="mt-1 flex items-center justify-between">
                          {ev.price && <span className="text-xs text-[#6272a4]">@{ev.price.toLocaleString()}원</span>}
                          {ev.amount != null && (
                            <span className={`text-sm font-semibold ${ev.amount >= 0 ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                              {ev.amount >= 0 ? "+" : ""}{won(Math.abs(ev.amount))}
                            </span>
                          )}
                          {ev.pnlPct != null && (
                            <span className={`text-xs font-medium ${ev.pnlPct >= 0 ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                              {ev.pnlPct >= 0 ? "+" : ""}{ev.pnlPct.toFixed(2)}%
                            </span>
                          )}
                        </div>
                        {ev.description && <p className="text-xs text-[#6272a4] mt-1">{ev.description}</p>}
                      </div>
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
