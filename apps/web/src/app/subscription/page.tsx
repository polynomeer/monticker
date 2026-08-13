"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";

interface Plan {
  id: number;
  code: "FREE" | "PRO" | "QUANT";
  name: string;
  price: number;
  currency: string;
  features: string[];
  isActive: boolean;
}

interface MySubscription {
  id: number;
  planCode: string;
  status: "ACTIVE" | "EXPIRED" | "CANCELLED";
  startedAt: string;
  expiresAt: string | null;
  cancelledAt: string | null;
}

interface PaymentRecord {
  id: number;
  planCode: string;
  amount: number;
  status: "PENDING" | "SUCCESS" | "FAILED" | "REFUNDED";
  pgProvider: string;
  paidAt: string | null;
  createdAt: string;
}

const PLAN_ACCENTS: Record<string, { border: string; badge: string; btn: string; icon: string }> = {
  FREE:  { border: "border-gray-200 dark:border-[#44475a]",      badge: "bg-gray-100 text-gray-700 dark:bg-[#44475a] dark:text-[#f8f8f2]",        btn: "bg-gray-100 text-gray-700 dark:bg-[#44475a] dark:text-[#f8f8f2] hover:bg-gray-200 dark:hover:bg-[#6272a4]",               icon: "🌱" },
  PRO:   { border: "border-[#bd93f9]/50",   badge: "bg-[#bd93f9]/20 text-[#bd93f9]",     btn: "bg-[#bd93f9] text-[#282a36] hover:bg-[#ff79c6]",               icon: "⚡" },
  QUANT: { border: "border-[#50fa7b]/50",   badge: "bg-[#50fa7b]/20 text-[#50fa7b]",     btn: "bg-[#50fa7b] text-[#282a36] hover:bg-[#8be9fd]",               icon: "🔬" },
};

function won(n: number) {
  return n === 0 ? "무료" : n.toLocaleString("ko-KR") + "원/월";
}

function PlanCard({
  plan,
  currentCode,
  onSubscribe,
  isPending,
}: {
  plan: Plan;
  currentCode: string | null;
  onSubscribe: (code: string) => void;
  isPending: boolean;
}) {
  const accent = PLAN_ACCENTS[plan.code] ?? PLAN_ACCENTS.FREE;
  const isCurrent = currentCode === plan.code;

  return (
    <div className={`relative flex flex-col p-6 rounded-2xl border bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line transition-all ${isCurrent ? accent.border + " ring-1 ring-[#bd93f9]/30" : accent.border}`}>
      {isCurrent && (
        <span className="absolute top-4 right-4 text-xs px-2 py-0.5 rounded-full bg-[#bd93f9]/20 text-[#bd93f9] font-medium">
          현재 플랜
        </span>
      )}
      <div className="flex items-center gap-2 mb-4">
        <span className="text-2xl">{accent.icon}</span>
        <div>
          <p className="text-xs text-gray-500 dark:text-[#6272a4]">{plan.name}</p>
          <p className="text-xl font-bold text-gray-900 dark:text-[#f8f8f2]">{won(plan.price)}</p>
        </div>
      </div>

      <ul className="flex-1 space-y-2 mb-6">
        {(plan.features ?? []).map((f, i) => (
          <li key={i} className="flex items-start gap-2 text-sm text-gray-500 dark:text-[#6272a4]">
            <span className="text-[#50fa7b] shrink-0">✓</span>
            <span>{f}</span>
          </li>
        ))}
      </ul>

      <button
        onClick={() => onSubscribe(plan.code)}
        disabled={isPending || isCurrent}
        className={`w-full py-2.5 rounded-xl text-sm font-semibold transition-all duration-150 active:scale-[0.98] disabled:opacity-40 disabled:active:scale-100 ${accent.btn}`}
      >
        {isCurrent ? "현재 플랜" : isPending ? "처리 중..." : plan.price === 0 ? "무료로 시작" : "구독하기"}
      </button>
    </div>
  );
}

export default function SubscriptionPage() {
  const [activeTab, setActiveTab] = useState<"plans" | "history">("plans");
  const { toast } = useToast();
  const qc = useQueryClient();

  const { data: plansData, isLoading: plansLoading } = useQuery<Plan[]>({
    queryKey: ["subscription", "plans"],
    queryFn: () => authFetch("/api/subscription/plans").then(r => r.json()),
  });

  const { data: mySub } = useQuery<MySubscription>({
    queryKey: ["subscription", "me"],
    queryFn: async () => {
      const res = await authFetch("/api/subscription/me");
      if (res.status === 404) return null;
      return res.json();
    },
  });

  const { data: paymentsData, isLoading: paymentsLoading } = useQuery<PaymentRecord[]>({
    queryKey: ["subscription", "payments"],
    queryFn: () => authFetch("/api/subscription/payments").then(r => r.json()),
    enabled: activeTab === "history",
  });

  const subscribeMutation = useMutation({
    mutationFn: (planCode: string) =>
      authFetch("/api/subscription/subscribe", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ planCode }),
      }).then(async r => {
        if (!r.ok) throw new Error(await r.text());
        return r.json();
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["subscription"] });
      toast({ type: "success", title: "구독 완료", message: "플랜이 변경되었습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "구독 실패", message: e.message }),
  });

  const cancelMutation = useMutation({
    mutationFn: () =>
      authFetch("/api/subscription/cancel", { method: "POST" }).then(async r => {
        if (!r.ok) throw new Error(await r.text());
        return r.json();
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["subscription"] });
      toast({ type: "success", title: "해지 완료", message: "구독이 해지되었습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "해지 실패", message: e.message }),
  });

  const currentCode = mySub?.status === "ACTIVE" ? mySub.planCode : null;

  const STATUS_LABELS: Record<string, { label: string; color: string }> = {
    ACTIVE:    { label: "활성", color: "text-[#50fa7b]" },
    EXPIRED:   { label: "만료", color: "text-[#ff5555]" },
    CANCELLED: { label: "해지", color: "text-gray-500 dark:text-[#6272a4]" },
  };

  const PAYMENT_LABELS: Record<string, { label: string; color: string }> = {
    SUCCESS:  { label: "결제 완료", color: "text-[#50fa7b]" },
    FAILED:   { label: "결제 실패", color: "text-[#ff5555]" },
    PENDING:  { label: "처리 중",   color: "text-[#ffb86c]" },
    REFUNDED: { label: "환불",      color: "text-gray-500 dark:text-[#6272a4]" },
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-8">
        <h1 className="text-xl font-bold text-gray-900 dark:text-[#f8f8f2]">구독 플랜</h1>
        <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">더 강력한 분석 도구로 업그레이드하세요</p>
      </div>

      {/* 현재 구독 상태 배너 */}
      {mySub && (
        <div className="mb-6 p-4 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line flex items-center justify-between gap-4">
          <div>
            <p className="text-xs text-gray-500 dark:text-[#6272a4]">현재 구독</p>
            <p className="font-semibold text-gray-900 dark:text-[#f8f8f2] mt-0.5">
              {mySub.planCode} 플랜{" "}
              <span className={`text-xs font-normal ${STATUS_LABELS[mySub.status]?.color}`}>
                {STATUS_LABELS[mySub.status]?.label}
              </span>
            </p>
            {mySub.expiresAt && (
              <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">
                만료일: {new Date(mySub.expiresAt).toLocaleDateString("ko-KR")}
              </p>
            )}
          </div>
          {mySub.status === "ACTIVE" && mySub.planCode !== "FREE" && (
            <button
              onClick={() => cancelMutation.mutate()}
              disabled={cancelMutation.isPending}
              className="shrink-0 px-3 py-1.5 rounded-lg border border-[#ff5555]/40 text-[#ff5555] text-xs hover:bg-[#ff5555]/10 transition-colors disabled:opacity-40"
            >
              {cancelMutation.isPending ? "처리 중..." : "구독 해지"}
            </button>
          )}
        </div>
      )}

      {/* 탭 */}
      <div className="flex gap-1 mb-6 border-b border-gray-200 dark:border-[#44475a]">
        {(["plans", "history"] as const).map(tab => (
          <button key={tab} onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
              ${activeTab === tab ? "border-blue-600 dark:border-[#bd93f9] text-blue-600 dark:text-[#bd93f9]" : "border-transparent text-gray-500 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2]"}`}>
            {tab === "plans" ? "플랜 선택" : "결제 내역"}
          </button>
        ))}
      </div>

      {/* 플랜 선택 */}
      {activeTab === "plans" && (
        plansLoading ? (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            {[1, 2, 3].map(i => <div key={i} className="h-72 rounded-2xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            {(plansData ?? []).map((plan: Plan) => (
              <PlanCard
                key={plan.id}
                plan={plan}
                currentCode={currentCode}
                onSubscribe={(code) => subscribeMutation.mutate(code)}
                isPending={subscribeMutation.isPending}
              />
            ))}
          </div>
        )
      )}

      {/* 결제 내역 */}
      {activeTab === "history" && (
        paymentsLoading ? (
          <div className="space-y-2">
            {[1,2,3].map(i => <div key={i} className="h-16 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}
          </div>
        ) : (paymentsData ?? []).length === 0 ? (
          <div className="text-center py-16 border border-dashed border-gray-300 dark:border-[#44475a] rounded-xl text-gray-500 dark:text-[#6272a4] text-sm">
            결제 내역이 없습니다.
          </div>
        ) : (
          <div className="space-y-2">
            {(paymentsData ?? []).map((p: PaymentRecord) => {
              const meta = PAYMENT_LABELS[p.status] ?? { label: p.status, color: "text-gray-900 dark:text-[#f8f8f2]" };
              return (
                <div key={p.id} className="flex items-center gap-3 p-4 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line">
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-gray-900 dark:text-[#f8f8f2]">{p.planCode} 플랜</span>
                      <span className={`text-xs ${meta.color}`}>{meta.label}</span>
                    </div>
                    <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">
                      {new Date(p.createdAt).toLocaleDateString("ko-KR")} · {p.pgProvider}
                    </p>
                  </div>
                  <p className={`text-sm font-semibold ${p.status === "SUCCESS" ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                    {p.amount.toLocaleString("ko-KR")}원
                  </p>
                </div>
              );
            })}
          </div>
        )
      )}

      <p className="text-xs text-gray-500 dark:text-[#6272a4] text-center mt-8">
        교육 목적 시뮬레이션 서비스입니다. 실제 투자 조언이 아닙니다.
      </p>
    </div>
  );
}
