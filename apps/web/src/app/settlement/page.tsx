"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";

interface PaperSettlement {
  id: number;
  tradeId: number;
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

interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

const STATUS_META: Record<string, { label: string; color: string; icon: string }> = {
  PENDING:  { label: "대기 중",   color: "text-[#ffb86c]", icon: "⏳" },
  SETTLED:  { label: "정산 완료", color: "text-[#50fa7b]", icon: "✅" },
  FAILED:   { label: "실패",      color: "text-[#ff5555]", icon: "❌" },
};

const SIDE_META: Record<string, { label: string; color: string }> = {
  BUY:  { label: "매수", color: "text-[#50fa7b]" },
  SELL: { label: "매도", color: "text-[#ff5555]" },
};

function won(n: number) {
  return n.toLocaleString("ko-KR") + "원";
}

function SettlementRow({ s }: { s: PaperSettlement }) {
  const [open, setOpen] = useState(false);
  const status = STATUS_META[s.status];
  const side   = SIDE_META[s.side];

  return (
    <Card className="overflow-hidden">
      <button
        onClick={() => setOpen(v => !v)}
        className="w-full flex items-center gap-3 p-4 text-left hover:bg-gray-50 dark:hover:bg-[#44475a]/20 transition-colors"
      >
        <span className="text-base">{status.icon}</span>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className={`text-xs font-medium ${side.color}`}>{side.label}</span>
            <span className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2]">
              {s.quantity.toLocaleString()}주 @ {won(s.fillPrice)}
            </span>
            <span className={`text-xs ${status.color}`}>{status.label}</span>
          </div>
          <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">
            정산 예정일: {new Date(s.settleDate).toLocaleDateString("ko-KR")}
            {s.settledAt && ` · 완료: ${new Date(s.settledAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}`}
          </p>
        </div>
        <div className="text-right shrink-0">
          <p className={`text-sm font-semibold ${s.side === "BUY" ? "text-[#ff5555]" : "text-[#50fa7b]"}`}>
            {s.side === "BUY" ? "-" : "+"}{won(s.netAmount)}
          </p>
          <p className="text-xs text-gray-500 dark:text-[#6272a4]">순액</p>
        </div>
        <span className={`text-gray-500 dark:text-[#6272a4] transition-transform ${open ? "rotate-180" : ""}`}>▾</span>
      </button>

      {open && (
        <div className="px-4 pb-4 border-t border-gray-100 dark:border-[#44475a]/50">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-3">
            {[
              { label: "거래 금액",   value: won(s.grossAmount) },
              { label: "수수료 (0.015%)", value: won(s.fee) },
              { label: "세금",        value: s.tax > 0 ? won(s.tax) : "—" },
              { label: "순 정산액",   value: won(s.netAmount) },
            ].map(row => (
              <div key={row.label} className="p-3 rounded-lg bg-gray-50 dark:bg-[#282a36]">
                <p className="text-xs text-gray-500 dark:text-[#6272a4]">{row.label}</p>
                <p className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2] mt-0.5">{row.value}</p>
              </div>
            ))}
          </div>
          {s.side === "SELL" && s.tax > 0 && (
            <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-2">
              * 매도세 = 증권거래세 0.15% + 농특세 0.03%
            </p>
          )}
        </div>
      )}
    </Card>
  );
}

export default function SettlementPage() {
  const [tab, setTab] = useState<"all" | "pending">("all");
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery<Page<PaperSettlement>>({
    queryKey: ["settlement", "paper", tab, page],
    queryFn: async () => {
      const url = tab === "pending"
        ? "/api/settlement/paper/pending"
        : `/api/settlement/paper?page=${page}&size=20`;
      const res = await authFetch(url);
      if (!res.ok) throw new Error("조회 실패");
      const json = await res.json();
      // pending endpoint returns a plain list
      if (Array.isArray(json)) return { content: json, totalElements: json.length, totalPages: 1, number: 0 };
      return json;
    },
  });

  const settlements: PaperSettlement[] = data?.content ?? [];

  return (
    <div className="max-w-3xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-8">
        <h1 className="text-xl font-bold text-gray-900 dark:text-[#f8f8f2]">모의투자 정산 내역</h1>
        <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">체결 후 T+2 영업일에 자동으로 정산됩니다</p>
      </div>

      {/* 요약 카드 */}
      {data && (
        <div className="grid grid-cols-3 gap-3 mb-6">
          {[
            { label: "전체",   count: data.totalElements,                                      color: "text-gray-900 dark:text-[#f8f8f2]" },
            { label: "대기 중", count: settlements.filter((s: PaperSettlement) => s.status === "PENDING").length,  color: "text-[#ffb86c]" },
            { label: "정산 완료", count: settlements.filter((s: PaperSettlement) => s.status === "SETTLED").length, color: "text-[#50fa7b]" },
          ].map(row => (
            <Card key={row.label} className="p-3 text-center">
              <p className={`text-xl font-bold ${row.color}`}>{row.count}</p>
              <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">{row.label}</p>
            </Card>
          ))}
        </div>
      )}

      {/* 탭 */}
      <div className="flex gap-1 mb-6 border-b border-gray-200 dark:border-[#44475a]">
        {(["all", "pending"] as const).map(t => (
          <button key={t} onClick={() => { setTab(t); setPage(0); }}
            className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
              ${tab === t ? "border-blue-600 dark:border-[#bd93f9] text-blue-600 dark:text-[#bd93f9]" : "border-transparent text-gray-500 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2]"}`}>
            {t === "all" ? "전체 내역" : "⏳ 대기 중"}
          </button>
        ))}
      </div>

      {/* 목록 */}
      {isLoading ? (
        <div className="space-y-3">
          {[1, 2, 3].map(i => <div key={i} className="h-20 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}
        </div>
      ) : settlements.length === 0 ? (
        <div className="text-center py-16 border border-dashed border-gray-300 dark:border-[#44475a] rounded-xl text-gray-500 dark:text-[#6272a4] text-sm">
          {tab === "pending" ? "대기 중인 정산이 없습니다." : "아직 정산 내역이 없습니다. 모의투자를 시작해보세요."}
        </div>
      ) : (
        <div className="space-y-3">
          {settlements.map(s => <SettlementRow key={s.id} s={s} />)}
        </div>
      )}

      {/* 페이지네이션 */}
      {tab === "all" && (data?.totalPages ?? 0) > 1 && (
        <div className="flex justify-center gap-3 mt-8">
          {page > 0 && (
            <button onClick={() => setPage(p => p - 1)}
              className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-[#44475a] text-gray-700 dark:text-[#f8f8f2] text-sm font-medium hover:bg-gray-200 dark:hover:bg-[#6272a4] active:scale-[0.98] transition-all duration-150">
              이전
            </button>
          )}
          {page < (data?.totalPages ?? 1) - 1 && (
            <button onClick={() => setPage(p => p + 1)}
              className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-[#44475a] text-gray-700 dark:text-[#f8f8f2] text-sm font-medium hover:bg-gray-200 dark:hover:bg-[#6272a4] active:scale-[0.98] transition-all duration-150">
              다음
            </button>
          )}
        </div>
      )}

      <p className="text-xs text-gray-500 dark:text-[#6272a4] text-center mt-8">
        정산은 매일 16:30 KST 자동 처리됩니다 (영업일 기준)
      </p>
    </div>
  );
}
