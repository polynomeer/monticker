"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { authFetch } from "@/services/api";

interface WalletMap {
  availableCash: number;
  reservedCash: number;
  holdingsValue: number;
  settlementPending: number;
  totalAssets: number;
  recentLedger: LedgerEvent[];
}

interface LedgerEvent {
  id: number;
  eventType: string;
  amount: number;
  balanceAfter: number | null;
  description: string | null;
  stockId: number | null;
  createdAt: string;
}

interface BehaviorScore {
  behaviorScore: number;
  survivalScore: number;
  feedback: string[];
  reliabilityNotes: Record<string, unknown>;
}

const EVENT_LABELS: Record<string, { label: string; color: string; icon: string }> = {
  DEPOSIT:        { label: "입금",     color: "text-[#50fa7b]", icon: "💵" },
  WITHDRAWAL:     { label: "출금",     color: "text-[#ff5555]", icon: "🏧" },
  FILL:           { label: "체결",     color: "text-[#bd93f9]", icon: "✅" },
  PARTIAL_FILL:   { label: "부분체결", color: "text-[#ffb86c]", icon: "🔸" },
  CASH_RESERVED:  { label: "예약",     color: "text-[#6272a4]", icon: "🔒" },
  CASH_UNRESERVED:{ label: "예약해제", color: "text-[#6272a4]", icon: "🔓" },
  FEE:            { label: "수수료",   color: "text-[#ff5555]", icon: "💸" },
  SETTLEMENT:     { label: "정산완료", color: "text-[#50fa7b]", icon: "🎯" },
};

function won(n: number) {
  return n.toLocaleString("ko-KR") + "원";
}

function ScoreMeter({ label, score, color }: { label: string; score: number; color: string }) {
  const grade = score >= 80 ? "A" : score >= 60 ? "B" : score >= 40 ? "C" : "D";
  const gradeColor = { A: "text-[#50fa7b]", B: "text-[#bd93f9]", C: "text-[#ffb86c]", D: "text-[#ff5555]" }[grade];
  return (
    <div className="p-4 rounded-xl border border-[#44475a] bg-[#282a36]">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs text-[#6272a4]">{label}</span>
        <span className={`text-lg font-bold ${gradeColor}`}>{grade}</span>
      </div>
      <div className="text-2xl font-bold text-[#f8f8f2] mb-2">{score}<span className="text-sm text-[#6272a4]">점</span></div>
      <div className="h-2 rounded-full bg-[#44475a] overflow-hidden">
        <div className={`h-full rounded-full transition-all ${color}`} style={{ width: `${score}%` }} />
      </div>
    </div>
  );
}

export default function WalletPage() {
  const [activeTab, setActiveTab] = useState<"map" | "ledger" | "score">("map");

  const { data: wallet, isLoading: walletLoading } = useQuery<WalletMap>({
    queryKey: ["wallet"],
    queryFn: async () => {
      const res = await authFetch("/api/wallet");
      if (!res.ok) throw new Error("지갑 정보 조회 실패");
      return res.json();
    },
    refetchInterval: 30_000,
  });

  const { data: score } = useQuery<BehaviorScore>({
    queryKey: ["wallet", "score"],
    queryFn: async () => {
      const res = await authFetch("/api/wallet/score");
      if (!res.ok) throw new Error("점수 조회 실패");
      return res.json();
    },
    enabled: activeTab === "score",
  });

  if (walletLoading) return (
    <div className="max-w-3xl mx-auto px-4 py-8 space-y-3">
      {[1,2,3].map(i => <div key={i} className="h-20 rounded-xl bg-[#44475a]/30 animate-pulse" />)}
    </div>
  );

  const total = wallet?.totalAssets ?? 0;

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-bold text-[#f8f8f2]">투자 월렛</h1>
          <p className="text-xs text-[#6272a4] mt-0.5">내 돈이 어디에 어떤 상태로 있는지</p>
        </div>
        <Link href="/wallet/replay" className="text-xs text-[#bd93f9] hover:underline">오늘의 리플레이 →</Link>
      </div>

      {/* 총 자산 */}
      {wallet && (
        <div className="mb-6 p-5 rounded-2xl border border-[#44475a] bg-[#21222c]">
          <p className="text-xs text-[#6272a4] mb-1">총 자산</p>
          <p className="text-3xl font-bold text-[#f8f8f2]">{won(total)}</p>
        </div>
      )}

      {/* 탭 */}
      <div className="flex gap-1 mb-6 border-b border-[#44475a]">
        {(["map","ledger","score"] as const).map(tab => (
          <button key={tab} onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
              ${activeTab === tab ? "border-[#bd93f9] text-[#bd93f9]" : "border-transparent text-[#6272a4] hover:text-[#f8f8f2]"}`}>
            {tab === "map" ? "돈의 이동 지도" : tab === "ledger" ? "원장 타임라인" : "투자 점수"}
          </button>
        ))}
      </div>

      {/* 돈의 이동 지도 */}
      {activeTab === "map" && wallet && (
        <div className="space-y-3">
          {[
            { label: "사용 가능 현금",  value: wallet.availableCash,     pct: total > 0 ? wallet.availableCash / total * 100 : 0,     color: "bg-[#50fa7b]",  icon: "💵" },
            { label: "주문 예약금",      value: wallet.reservedCash,      pct: total > 0 ? wallet.reservedCash / total * 100 : 0,      color: "bg-[#ffb86c]",  icon: "🔒" },
            { label: "보유 주식 평가액", value: wallet.holdingsValue,     pct: total > 0 ? wallet.holdingsValue / total * 100 : 0,     color: "bg-[#bd93f9]",  icon: "📈" },
            { label: "정산 대기 금액",   value: wallet.settlementPending, pct: total > 0 ? wallet.settlementPending / total * 100 : 0, color: "bg-[#6272a4]",  icon: "⏳" },
          ].map(row => (
            <div key={row.label} className="p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm text-[#6272a4] flex items-center gap-1.5">{row.icon} {row.label}</span>
                <span className="text-sm font-semibold text-[#f8f8f2]">{won(row.value)}</span>
              </div>
              <div className="h-1.5 rounded-full bg-[#44475a] overflow-hidden">
                <div className={`h-full rounded-full ${row.color}`} style={{ width: `${row.pct.toFixed(1)}%` }} />
              </div>
              <p className="text-xs text-[#6272a4] mt-1 text-right">{row.pct.toFixed(1)}%</p>
            </div>
          ))}
        </div>
      )}

      {/* 원장 타임라인 */}
      {activeTab === "ledger" && (
        <div className="space-y-2">
          {(wallet?.recentLedger ?? []).length === 0 ? (
            <div className="text-center py-12 text-[#6272a4] text-sm border border-dashed border-[#44475a] rounded-xl">
              아직 거래 기록이 없습니다. 모의투자를 시작해보세요.
            </div>
          ) : (wallet?.recentLedger ?? []).map((ev: LedgerEvent) => {
            const meta = EVENT_LABELS[ev.eventType] ?? { label: ev.eventType, color: "text-[#f8f8f2]", icon: "•" };
            const sign = ev.amount > 0 ? "+" : "";
            return (
              <div key={ev.id} className="flex items-center gap-3 p-3 rounded-xl border border-[#44475a] bg-[#21222c]">
                <span className="text-lg">{meta.icon}</span>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className={`text-xs font-medium ${meta.color}`}>{meta.label}</span>
                    {ev.description && <span className="text-xs text-[#6272a4] truncate">{ev.description}</span>}
                  </div>
                  <p className="text-xs text-[#6272a4] mt-0.5">
                    {new Date(ev.createdAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
                  </p>
                </div>
                <div className="text-right shrink-0">
                  <p className={`text-sm font-semibold ${ev.amount >= 0 ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                    {sign}{won(Math.abs(ev.amount))}
                  </p>
                  {ev.balanceAfter != null && (
                    <p className="text-xs text-[#6272a4]">잔고 {won(ev.balanceAfter)}</p>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 투자 점수 */}
      {activeTab === "score" && (
        <div className="space-y-4">
          {!score ? (
            <div className="text-center py-12 text-[#6272a4] text-sm">로딩 중...</div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3">
                <ScoreMeter label="투자 행동 점수" score={score.behaviorScore} color="bg-[#bd93f9]" />
                <ScoreMeter label="투자 생존 점수" score={score.survivalScore} color="bg-[#50fa7b]" />
              </div>

              {score.feedback.length > 0 && (
                <div className="p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
                  <p className="text-xs font-semibold text-[#f8f8f2] mb-3">📋 피드백</p>
                  <ul className="space-y-1.5">
                    {score.feedback.map((fb: string, i: number) => (
                      <li key={i} className="text-xs text-[#6272a4] flex gap-2">
                        <span>•</span><span>{fb}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <p className="text-xs text-[#6272a4] text-center">
                모의투자 전용 교육용 피드백입니다. 실제 투자 조언이 아닙니다.
              </p>
            </>
          )}
        </div>
      )}
    </div>
  );
}
