"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import {
  type Icon, ArrowLineDown, ArrowLineUp, CheckCircle, CircleHalf, Lock, LockOpen,
  Receipt, Target, ClipboardText, CreditCard, Coins, Bank, Buildings, Wallet, ChartLineUp, HourglassMedium, Circle,
} from "@phosphor-icons/react";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";

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

function ScoreMeter({ label, score, color }: { label: string; score: number; color: string }) {
  const grade = score >= 80 ? "A" : score >= 60 ? "B" : score >= 40 ? "C" : "D";
  const gradeColor = { A: "text-dracula-green", B: "text-dracula-purple", C: "text-dracula-orange", D: "text-dracula-red" }[grade];
  return (
    <div className="p-4 rounded-xl border border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-dracula-bg">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs text-gray-500 dark:text-dracula-comment">{label}</span>
        <span className={`text-lg font-bold ${gradeColor}`}>{grade}</span>
      </div>
      <div className="text-2xl font-bold text-gray-900 dark:text-dracula-fg mb-2">{score}<span className="text-sm text-gray-500 dark:text-dracula-comment">점</span></div>
      <div className="h-2 rounded-full bg-gray-200 dark:bg-dracula-line overflow-hidden">
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
      {[1,2,3].map(i => <div key={i} className="h-20 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}
    </div>
  );

  const total = wallet?.totalAssets ?? 0;

  return (
    <div className="max-w-3xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-bold text-gray-900 dark:text-dracula-fg">투자 월렛</h1>
          <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">내 돈이 어디에 어떤 상태로 있는지</p>
        </div>
        <Link href="/wallet/replay" className="text-xs text-dracula-purple hover:underline">오늘의 리플레이 →</Link>
      </div>

      {/* 총 자산 */}
      {wallet && (
        <Card className="p-5" outerClassName="mb-6">
          <p className="text-xs text-gray-500 dark:text-dracula-comment mb-1">총 자산</p>
          <p className="text-3xl font-bold text-gray-900 dark:text-dracula-fg">{won(total)}</p>
        </Card>
      )}

      {/* 탭 */}
      <div className="flex gap-1 mb-6 border-b border-gray-200 dark:border-dracula-line">
        {(["map","ledger","score"] as const).map(tab => (
          <button key={tab} onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
              ${activeTab === tab ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple" : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
            {tab === "map" ? "돈의 이동 지도" : tab === "ledger" ? "원장 타임라인" : "투자 점수"}
          </button>
        ))}
      </div>

      {/* 돈의 이동 지도 */}
      {activeTab === "map" && wallet && (
        <div className="space-y-3">
          {[
            { label: "사용 가능 현금",  value: wallet.availableCash,     pct: total > 0 ? wallet.availableCash / total * 100 : 0,     color: "bg-dracula-green",  icon: Wallet },
            { label: "주문 예약금",      value: wallet.reservedCash,      pct: total > 0 ? wallet.reservedCash / total * 100 : 0,      color: "bg-dracula-orange",  icon: Lock },
            { label: "보유 주식 평가액", value: wallet.holdingsValue,     pct: total > 0 ? wallet.holdingsValue / total * 100 : 0,     color: "bg-dracula-purple",  icon: ChartLineUp },
            { label: "정산 대기 금액",   value: wallet.settlementPending, pct: total > 0 ? wallet.settlementPending / total * 100 : 0, color: "bg-dracula-comment",  icon: HourglassMedium },
          ].map(row => (
            <Card key={row.label} className="p-4">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm text-gray-500 dark:text-dracula-comment flex items-center gap-1.5">
                  <row.icon size={14} weight="bold" aria-hidden /> {row.label}
                </span>
                <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{won(row.value)}</span>
              </div>
              <div className="h-1.5 rounded-full bg-gray-200 dark:bg-dracula-line overflow-hidden">
                <div className={`h-full rounded-full ${row.color}`} style={{ width: `${row.pct.toFixed(1)}%` }} />
              </div>
              <p className="text-xs text-gray-500 dark:text-dracula-comment mt-1 text-right">{row.pct.toFixed(1)}%</p>
            </Card>
          ))}
        </div>
      )}

      {/* 원장 타임라인 */}
      {activeTab === "ledger" && (
        <div className="space-y-2">
          {(wallet?.recentLedger ?? []).length === 0 ? (
            <div className="text-center py-12 text-gray-500 dark:text-dracula-comment text-sm border border-dashed border-gray-300 dark:border-dracula-line rounded-xl">
              아직 거래 기록이 없습니다. 모의투자를 시작해보세요.
            </div>
          ) : (wallet?.recentLedger ?? []).map((ev: LedgerEvent) => {
            const meta = EVENT_LABELS[ev.eventType] ?? { label: ev.eventType, color: "text-gray-900 dark:text-dracula-fg", icon: Circle };
            const sign = ev.amount > 0 ? "+" : "";
            return (
              <Card key={ev.id} className="flex items-center gap-3 p-3">
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
          })}
        </div>
      )}

      {/* 투자 점수 */}
      {activeTab === "score" && (
        <div className="space-y-4">
          {!score ? (
            <div className="text-center py-12 text-gray-500 dark:text-dracula-comment text-sm">로딩 중...</div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3">
                <ScoreMeter label="투자 행동 점수" score={score.behaviorScore} color="bg-dracula-purple" />
                <ScoreMeter label="투자 생존 점수" score={score.survivalScore} color="bg-dracula-green" />
              </div>

              {score.feedback.length > 0 && (
                <Card className="p-4">
                  <p className="text-xs font-semibold text-gray-900 dark:text-dracula-fg mb-3 flex items-center gap-1.5">
                    <ClipboardText size={14} weight="bold" aria-hidden /> 피드백
                  </p>
                  <ul className="space-y-1.5">
                    {score.feedback.map((fb: string, i: number) => (
                      <li key={i} className="text-xs text-gray-500 dark:text-dracula-comment flex gap-2">
                        <span>•</span><span>{fb}</span>
                      </li>
                    ))}
                  </ul>
                </Card>
              )}

              <p className="text-xs text-gray-500 dark:text-dracula-comment text-center">
                모의투자 전용 교육용 피드백입니다. 실제 투자 조언이 아닙니다.
              </p>
            </>
          )}
        </div>
      )}
    </div>
  );
}
