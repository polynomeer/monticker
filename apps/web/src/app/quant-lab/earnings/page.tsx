"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";

interface EarningSummary {
  strategyId: number;
  totalNet: number;
}

interface CreatorEarning {
  id: number;
  strategyId: number;
  subscriberId: number;
  grossAmount: number;
  platformFee: number;
  netAmount: number;
  status: "AVAILABLE" | "PAID_OUT" | "CANCELLED";
  earnedAt: string;
}

interface CreatorPayout {
  id: number;
  amount: number;
  bankName: string;
  accountNumber: string;
  accountHolder: string;
  status: "REQUESTED" | "APPROVED" | "REJECTED" | "PAID";
  requestedAt: string;
  paidAt: string | null;
}

interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

function won(n: number) {
  return n.toLocaleString("ko-KR") + "원";
}

const PAYOUT_STATUS: Record<string, { label: string; color: string }> = {
  REQUESTED: { label: "검토 중",   color: "text-[#ffb86c]" },
  APPROVED:  { label: "승인됨",    color: "text-[#8be9fd]" },
  REJECTED:  { label: "거절됨",    color: "text-[#ff5555]" },
  PAID:      { label: "지급 완료", color: "text-[#50fa7b]" },
};

const EARNING_STATUS: Record<string, { label: string; color: string }> = {
  AVAILABLE: { label: "지급 가능", color: "text-[#50fa7b]" },
  PAID_OUT:  { label: "지급됨",    color: "text-[#6272a4]" },
  CANCELLED: { label: "취소",      color: "text-[#ff5555]" },
};

function PayoutModal({ available, onClose, onSubmit }: {
  available: number;
  onClose: () => void;
  onSubmit: (form: { amount: number; bankName: string; accountNumber: string; accountHolder: string }) => void;
}) {
  const [form, setForm] = useState({ amount: "", bankName: "", accountNumber: "", accountHolder: "" });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const amount = Number(form.amount);
    if (amount < 10000) return;
    onSubmit({ amount, bankName: form.bankName, accountNumber: form.accountNumber, accountHolder: form.accountHolder });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="w-full max-w-md mx-4 p-6 rounded-2xl border border-[#44475a] bg-[#282a36] shadow-2xl">
        <h2 className="text-base font-bold text-[#f8f8f2] mb-1">수익 출금 신청</h2>
        <p className="text-xs text-[#6272a4] mb-5">출금 가능: <span className="text-[#50fa7b] font-semibold">{won(available)}</span> · 최소 10,000원</p>

        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="text-xs text-[#6272a4] block mb-1">출금 금액 (원)</label>
            <input
              type="number"
              required
              min={10000}
              max={available}
              value={form.amount}
              onChange={e => setForm(f => ({ ...f, amount: e.target.value }))}
              placeholder="10,000 이상"
              className="w-full px-3 py-2 rounded-lg bg-[#21222c] border border-[#44475a] text-[#f8f8f2] text-sm focus:outline-none focus:border-[#bd93f9] transition-colors"
            />
          </div>
          <div>
            <label className="text-xs text-[#6272a4] block mb-1">은행명</label>
            <input
              type="text"
              required
              value={form.bankName}
              onChange={e => setForm(f => ({ ...f, bankName: e.target.value }))}
              placeholder="예: 카카오뱅크"
              className="w-full px-3 py-2 rounded-lg bg-[#21222c] border border-[#44475a] text-[#f8f8f2] text-sm focus:outline-none focus:border-[#bd93f9] transition-colors"
            />
          </div>
          <div>
            <label className="text-xs text-[#6272a4] block mb-1">계좌번호</label>
            <input
              type="text"
              required
              value={form.accountNumber}
              onChange={e => setForm(f => ({ ...f, accountNumber: e.target.value }))}
              placeholder="- 없이 입력"
              className="w-full px-3 py-2 rounded-lg bg-[#21222c] border border-[#44475a] text-[#f8f8f2] text-sm focus:outline-none focus:border-[#bd93f9] transition-colors"
            />
          </div>
          <div>
            <label className="text-xs text-[#6272a4] block mb-1">예금주</label>
            <input
              type="text"
              required
              value={form.accountHolder}
              onChange={e => setForm(f => ({ ...f, accountHolder: e.target.value }))}
              placeholder="이름"
              className="w-full px-3 py-2 rounded-lg bg-[#21222c] border border-[#44475a] text-[#f8f8f2] text-sm focus:outline-none focus:border-[#bd93f9] transition-colors"
            />
          </div>

          <div className="flex gap-2 pt-2">
            <button type="button" onClick={onClose}
              className="flex-1 py-2.5 rounded-xl border border-[#44475a] text-[#6272a4] text-sm hover:bg-[#44475a]/30 transition-colors">
              취소
            </button>
            <button type="submit"
              className="flex-1 py-2.5 rounded-xl bg-[#bd93f9] text-[#282a36] text-sm font-semibold hover:bg-[#ff79c6] transition-colors">
              신청하기
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function EarningsPage() {
  const [tab, setTab] = useState<"overview" | "earnings" | "payouts">("overview");
  const [earningPage, setEarningPage] = useState(0);
  const [payoutPage, setPayoutPage] = useState(0);
  const [showModal, setShowModal] = useState(false);
  const { toast } = useToast();
  const qc = useQueryClient();

  const { data: balance = 0 } = useQuery<number>({
    queryKey: ["earnings", "balance"],
    queryFn: () => authFetch("/api/settlement/strategy/earnings/summary/balance").then(r => r.json()),
  });

  const { data: byStrategy } = useQuery<EarningSummary[]>({
    queryKey: ["earnings", "by-strategy"],
    queryFn: (): Promise<EarningSummary[]> => authFetch("/api/settlement/strategy/earnings/summary").then(r => r.json()),
    enabled: tab === "overview",
  });

  const { data: earningsData } = useQuery<Page<CreatorEarning>>({
    queryKey: ["earnings", "list", earningPage],
    queryFn: () =>
      authFetch(`/api/settlement/strategy/earnings?page=${earningPage}&size=20`).then(r => r.json()),
    enabled: tab === "earnings",
  });

  const { data: payoutsData } = useQuery<Page<CreatorPayout>>({
    queryKey: ["earnings", "payouts", payoutPage],
    queryFn: () =>
      authFetch(`/api/settlement/strategy/payouts?page=${payoutPage}&size=20`).then(r => r.json()),
    enabled: tab === "payouts",
  });

  const payoutMutation = useMutation({
    mutationFn: (body: { amount: number; bankName: string; accountNumber: string; accountHolder: string }) =>
      authFetch("/api/settlement/strategy/payout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }).then(async r => {
        if (!r.ok) throw new Error(await r.text());
        return r.json();
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["earnings"] });
      setShowModal(false);
      toast({ type: "success", title: "출금 신청 완료", message: "검토 후 지급됩니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "출금 신청 실패", message: e.message }),
  });

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      {showModal && (
        <PayoutModal
          available={balance}
          onClose={() => setShowModal(false)}
          onSubmit={(form) => payoutMutation.mutate(form)}
        />
      )}

      {/* 헤더 */}
      <div className="flex items-center gap-3 mb-8">
        <Link href="/quant-lab" className="text-[#6272a4] hover:text-[#f8f8f2] text-sm">← Quant Lab</Link>
        <h1 className="text-xl font-bold text-[#f8f8f2]">제작자 수익</h1>
      </div>

      {/* 출금 가능 잔액 카드 */}
      <div className="mb-6 p-5 rounded-2xl border border-[#bd93f9]/30 bg-[#21222c]">
        <div className="flex items-end justify-between gap-4">
          <div>
            <p className="text-xs text-[#6272a4] mb-1">출금 가능 잔액</p>
            <p className="text-3xl font-bold text-[#f8f8f2]">{won(balance)}</p>
            <p className="text-xs text-[#6272a4] mt-1">제작자 70% 수익분 · 최소 출금 10,000원</p>
          </div>
          <button
            onClick={() => setShowModal(true)}
            disabled={balance < 10000}
            className="shrink-0 px-5 py-2.5 rounded-xl bg-[#bd93f9] text-[#282a36] text-sm font-semibold hover:bg-[#ff79c6] transition-colors disabled:opacity-40"
          >
            출금 신청
          </button>
        </div>
      </div>

      {/* 탭 */}
      <div className="flex gap-1 mb-6 border-b border-[#44475a]">
        {(["overview", "earnings", "payouts"] as const).map(t => (
          <button key={t} onClick={() => setTab(t)}
            className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
              ${tab === t ? "border-[#bd93f9] text-[#bd93f9]" : "border-transparent text-[#6272a4] hover:text-[#f8f8f2]"}`}>
            {t === "overview" ? "전략별 수익" : t === "earnings" ? "수익 내역" : "출금 내역"}
          </button>
        ))}
      </div>

      {/* 전략별 수익 요약 */}
      {tab === "overview" && (
        (byStrategy ?? []).length === 0 ? (
          <div className="text-center py-16 border border-dashed border-[#44475a] rounded-xl text-[#6272a4] text-sm">
            아직 수익이 없습니다. 전략을 공유하고 구독자를 모아보세요.
            <Link href="/quant-lab/builder" className="block mt-3 text-[#bd93f9] hover:underline">룰셋 만들기 →</Link>
          </div>
        ) : (
          <div className="space-y-3">
            {(byStrategy ?? []).map((row: EarningSummary, i: number) => (
              <div key={row.strategyId} className="p-4 rounded-xl border border-[#44475a] bg-[#21222c] flex items-center gap-4">
                <span className="text-lg text-[#6272a4] font-mono w-6 text-center">{i + 1}</span>
                <div className="flex-1">
                  <p className="text-sm font-medium text-[#f8f8f2]">전략 #{row.strategyId}</p>
                  <p className="text-xs text-[#6272a4]">누적 순수익</p>
                </div>
                <p className="text-base font-bold text-[#50fa7b]">{won(row.totalNet)}</p>
              </div>
            ))}
          </div>
        )
      )}

      {/* 수익 내역 */}
      {tab === "earnings" && (
        (() => {
          const items: CreatorEarning[] = earningsData?.content ?? [];
          return items.length === 0 ? (
            <div className="text-center py-16 border border-dashed border-[#44475a] rounded-xl text-[#6272a4] text-sm">
              수익 내역이 없습니다.
            </div>
          ) : (
            <>
              <div className="space-y-2">
                {items.map(e => {
                  const meta = EARNING_STATUS[e.status];
                  return (
                    <div key={e.id} className="flex items-center gap-3 p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-medium text-[#f8f8f2]">전략 #{e.strategyId}</span>
                          <span className={`text-xs ${meta.color}`}>{meta.label}</span>
                        </div>
                        <p className="text-xs text-[#6272a4] mt-0.5">
                          {new Date(e.earnedAt).toLocaleDateString("ko-KR")} ·
                          총액 {won(e.grossAmount)} → 플랫폼 수수료 {won(e.platformFee)}
                        </p>
                      </div>
                      <p className="text-sm font-bold text-[#50fa7b] shrink-0">+{won(e.netAmount)}</p>
                    </div>
                  );
                })}
              </div>
              {(earningsData?.totalPages ?? 1) > 1 && (
                <div className="flex justify-center gap-3 mt-6">
                  {earningPage > 0 && (
                    <button onClick={() => setEarningPage(p => p - 1)}
                      className="px-4 py-2 rounded-lg bg-[#44475a] text-[#f8f8f2] text-sm hover:bg-[#6272a4] transition-colors">이전</button>
                  )}
                  {earningPage < (earningsData?.totalPages ?? 1) - 1 && (
                    <button onClick={() => setEarningPage(p => p + 1)}
                      className="px-4 py-2 rounded-lg bg-[#44475a] text-[#f8f8f2] text-sm hover:bg-[#6272a4] transition-colors">다음</button>
                  )}
                </div>
              )}
            </>
          );
        })()
      )}

      {/* 출금 내역 */}
      {tab === "payouts" && (
        (() => {
          const items: CreatorPayout[] = payoutsData?.content ?? [];
          return items.length === 0 ? (
            <div className="text-center py-16 border border-dashed border-[#44475a] rounded-xl text-[#6272a4] text-sm">
              출금 내역이 없습니다.
            </div>
          ) : (
            <div className="space-y-2">
              {items.map(p => {
                const meta = PAYOUT_STATUS[p.status];
                return (
                  <div key={p.id} className="p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
                    <div className="flex items-center justify-between gap-4">
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-bold text-[#f8f8f2]">{won(p.amount)}</span>
                          <span className={`text-xs ${meta.color}`}>{meta.label}</span>
                        </div>
                        <p className="text-xs text-[#6272a4] mt-0.5">
                          {p.bankName} {p.accountNumber.slice(-4).padStart(p.accountNumber.length, "•")} ({p.accountHolder})
                        </p>
                        <p className="text-xs text-[#6272a4]">
                          신청일: {new Date(p.requestedAt).toLocaleDateString("ko-KR")}
                          {p.paidAt && ` · 지급일: ${new Date(p.paidAt).toLocaleDateString("ko-KR")}`}
                        </p>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          );
        })()
      )}

      <p className="text-xs text-[#6272a4] text-center mt-8">
        수익은 구독자 결제 금액의 70%입니다. 최소 출금액 10,000원.
      </p>
    </div>
  );
}
