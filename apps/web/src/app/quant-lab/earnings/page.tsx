"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import type { EarningsSummaryResponse, EarningSummary, CreatorEarning, CreatorPayout, PageResponse } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";

function won(n: number) {
  return n.toLocaleString("ko-KR") + "원";
}

const PAYOUT_STATUS: Record<string, { label: string; color: string }> = {
  REQUESTED: { label: "검토 중",   color: "text-dracula-orange" },
  APPROVED:  { label: "승인됨",    color: "text-dracula-cyan" },
  REJECTED:  { label: "거절됨",    color: "text-dracula-red" },
  PAID:      { label: "지급 완료", color: "text-dracula-green" },
};

const EARNING_STATUS: Record<string, { label: string; color: string }> = {
  AVAILABLE: { label: "지급 가능", color: "text-dracula-green" },
  PAID_OUT:  { label: "지급됨",    color: "text-dracula-comment" },
  CANCELLED: { label: "취소",      color: "text-dracula-red" },
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
      <Card className="p-6" outerClassName="w-full max-w-md mx-4 animate-fade-up">
        <h2 className="text-base font-bold text-gray-900 dark:text-dracula-fg mb-1">수익 출금 신청</h2>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mb-5">출금 가능: <span className="text-dracula-green font-semibold">{won(available)}</span> · 최소 10,000원</p>

        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label htmlFor="payout-amount" className="text-xs text-gray-500 dark:text-dracula-comment block mb-1">출금 금액 (원)</label>
            <input
              id="payout-amount"
              type="number"
              required
              min={10000}
              max={available}
              value={form.amount}
              onChange={e => setForm(f => ({ ...f, amount: e.target.value }))}
              placeholder="10,000 이상"
              className="w-full px-3 py-2 rounded-lg bg-white dark:bg-dracula-surface border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm focus:outline-none focus:border-dracula-purple transition-colors"
            />
          </div>
          <div>
            <label htmlFor="payout-bank" className="text-xs text-gray-500 dark:text-dracula-comment block mb-1">은행명</label>
            <input
              id="payout-bank"
              type="text"
              required
              value={form.bankName}
              onChange={e => setForm(f => ({ ...f, bankName: e.target.value }))}
              placeholder="예: 카카오뱅크"
              className="w-full px-3 py-2 rounded-lg bg-white dark:bg-dracula-surface border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm focus:outline-none focus:border-dracula-purple transition-colors"
            />
          </div>
          <div>
            <label htmlFor="payout-account" className="text-xs text-gray-500 dark:text-dracula-comment block mb-1">계좌번호</label>
            <input
              id="payout-account"
              type="text"
              required
              value={form.accountNumber}
              onChange={e => setForm(f => ({ ...f, accountNumber: e.target.value }))}
              placeholder="- 없이 입력"
              className="w-full px-3 py-2 rounded-lg bg-white dark:bg-dracula-surface border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm focus:outline-none focus:border-dracula-purple transition-colors"
            />
          </div>
          <div>
            <label htmlFor="payout-holder" className="text-xs text-gray-500 dark:text-dracula-comment block mb-1">예금주</label>
            <input
              id="payout-holder"
              type="text"
              required
              value={form.accountHolder}
              onChange={e => setForm(f => ({ ...f, accountHolder: e.target.value }))}
              placeholder="이름"
              className="w-full px-3 py-2 rounded-lg bg-white dark:bg-dracula-surface border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm focus:outline-none focus:border-dracula-purple transition-colors"
            />
          </div>

          <div className="flex gap-2 pt-2">
            <button type="button" onClick={onClose}
              className="flex-1 py-2.5 rounded-xl border border-gray-300 dark:border-dracula-line text-gray-500 dark:text-dracula-comment text-sm hover:bg-gray-50 dark:hover:bg-dracula-line/30 active:scale-[0.98] transition-all duration-150">
              취소
            </button>
            <button type="submit"
              className="flex-1 py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150">
              신청하기
            </button>
          </div>
        </form>
      </Card>
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

  const { data: summary } = useQuery<EarningsSummaryResponse>({
    queryKey: ["earnings", "summary"],
    queryFn: () => authFetch("/api/settlement/strategy/earnings/summary").then(r => r.json()),
  });
  const balance = summary?.availableBalance ?? 0;
  const byStrategy = summary?.byStrategy;

  const { data: earningsData } = useQuery<PageResponse<CreatorEarning>>({
    queryKey: ["earnings", "list", earningPage],
    queryFn: () =>
      authFetch(`/api/settlement/strategy/earnings?page=${earningPage}&size=20`).then(r => r.json()),
    enabled: tab === "earnings",
  });

  const { data: payoutsData } = useQuery<PageResponse<CreatorPayout>>({
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

  const btnCls = "px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-xs font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment active:scale-[0.98] transition-all duration-150";

  return (
    <div className="animate-fade-up">
      {showModal && (
        <PayoutModal
          available={balance}
          onClose={() => setShowModal(false)}
          onSubmit={(form) => payoutMutation.mutate(form)}
        />
      )}

      {/* ── 헤더 바 ────────────────────────────────────────────── */}
      <div className="flex items-center gap-3 px-4 sm:px-6 py-4
                      border-b border-gray-100 dark:border-white/5 bg-white dark:bg-dracula-bg">
        <Link href="/quant-lab"
          className="text-gray-400 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg text-xs transition-colors shrink-0">
          ← Quant Lab
        </Link>
        <div className="w-px h-5 bg-gray-200 dark:bg-dracula-line" />
        <h1 className="text-sm font-bold text-gray-900 dark:text-dracula-fg">제작자 수익 대시보드</h1>
      </div>

      {/* ── 2컬럼 레이아웃 ─────────────────────────────────────── */}
      <div className="flex flex-col lg:flex-row lg:divide-x dark:lg:divide-white/5
                      lg:min-h-[calc(100vh-108px)]">

        {/* ===== 좌측: 잔액 카드 + 출금 버튼 (스티키) ===== */}
        <aside className="w-full lg:w-[260px] lg:flex-none lg:sticky lg:top-14
                          lg:h-[calc(100vh-108px)] lg:overflow-y-auto
                          px-4 py-5 space-y-5 dark:bg-[#1e202a]
                          border-b lg:border-b-0 border-gray-100 dark:border-white/5">

          {/* 출금 가능 잔액 */}
          <div className="rounded-xl border border-dracula-purple/30 bg-gradient-to-br
                          from-dracula-purple/5 to-transparent p-4">
            <p className="text-[10px] uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-2">
              출금 가능 잔액
            </p>
            <p className="text-3xl font-bold tabular-nums text-gray-900 dark:text-dracula-fg leading-none mb-1">
              {won(balance)}
            </p>
            <p className="text-[10px] text-gray-400 dark:text-dracula-comment leading-tight">
              제작자 70% 수익분<br />최소 출금 10,000원
            </p>
            <button
              onClick={() => setShowModal(true)}
              disabled={balance < 10000}
              className="mt-4 w-full py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-xs font-bold hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100"
            >
              출금 신청
            </button>
          </div>

          {/* 전략별 누적 수익 요약 (사이드바에 상위 5개) */}
          {(byStrategy ?? []).length > 0 && (
            <div>
              <p className="text-[10px] uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-2">
                전략별 누적 수익
              </p>
              <div className="space-y-2">
                {(byStrategy ?? []).slice(0, 5).map((row: EarningSummary, i: number) => (
                  <div key={row.strategyId}
                    className="flex items-center justify-between py-1.5 border-b border-gray-100 dark:border-dracula-line/30 last:border-0">
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] w-4 text-right font-mono text-gray-300 dark:text-dracula-line">{i + 1}</span>
                      <span className="text-xs text-gray-700 dark:text-dracula-fg">전략 #{row.strategyId}</span>
                    </div>
                    <span className="text-xs font-bold tabular-nums text-dracula-green">{won(row.totalNet)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <p className="text-[9px] text-gray-300 dark:text-dracula-line leading-snug">
            수익은 구독자 결제 금액의 70%입니다.
          </p>
        </aside>

        {/* ===== 우측: 탭 + 콘텐츠 ===== */}
        <main className="flex-1 min-w-0 px-4 sm:px-6 py-5">

          {/* 탭 바 */}
          <div className="flex gap-1 mb-5 border-b border-gray-200 dark:border-dracula-line">
            {(["overview", "earnings", "payouts"] as const).map(t => (
              <button key={t} onClick={() => setTab(t)}
                className={`px-3 py-2 text-sm font-medium transition-colors duration-150 border-b-2 -mb-px
                  ${tab === t
                    ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple"
                    : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
                  }`}>
                {t === "overview" ? "전략별 수익" : t === "earnings" ? "수익 내역" : "출금 내역"}
              </button>
            ))}
          </div>

          {/* 전략별 수익 — 그리드 */}
          {tab === "overview" && (
            (byStrategy ?? []).length === 0 ? (
              <div className="flex flex-col items-center justify-center py-20 text-center border border-dashed border-gray-200 dark:border-dracula-line rounded-xl">
                <p className="text-sm text-gray-500 dark:text-dracula-comment mb-1">아직 수익이 없습니다.</p>
                <p className="text-xs text-gray-400 dark:text-dracula-comment mb-4">전략을 공유하고 구독자를 모아보세요.</p>
                <Link href="/quant-lab/builder"
                  className="px-4 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-xs font-semibold hover:opacity-90 active:scale-[0.98] transition-all">
                  룰셋 만들기 →
                </Link>
              </div>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
                {(byStrategy ?? []).map((row: EarningSummary, i: number) => (
                  <Card key={row.strategyId} className="p-4 flex items-center gap-3" hover>
                    <span className="text-base text-gray-300 dark:text-dracula-line font-mono w-5 text-right shrink-0">{i + 1}</span>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">전략 #{row.strategyId}</p>
                      <p className="text-[10px] text-gray-400 dark:text-dracula-comment">누적 순수익</p>
                    </div>
                    <p className="text-base font-bold tabular-nums text-dracula-green shrink-0">{won(row.totalNet)}</p>
                  </Card>
                ))}
              </div>
            )
          )}

          {/* 수익 내역 — 테이블 */}
          {tab === "earnings" && (() => {
            const items: CreatorEarning[] = earningsData?.content ?? [];
            return items.length === 0 ? (
              <div className="text-center py-20 text-sm text-gray-400 dark:text-dracula-comment border border-dashed border-gray-200 dark:border-dracula-line rounded-xl">
                수익 내역이 없습니다.
              </div>
            ) : (
              <>
                <Card className="overflow-hidden">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-gray-200 dark:border-dracula-line bg-gray-50/60 dark:bg-transparent text-gray-400 dark:text-dracula-comment">
                        <th className="px-4 py-2.5 text-left font-medium">전략</th>
                        <th className="px-4 py-2.5 text-left font-medium">날짜</th>
                        <th className="px-4 py-2.5 text-right font-medium">총액</th>
                        <th className="px-4 py-2.5 text-right font-medium">수수료</th>
                        <th className="px-4 py-2.5 text-right font-medium">순수익</th>
                        <th className="px-4 py-2.5 text-left font-medium">상태</th>
                      </tr>
                    </thead>
                    <tbody>
                      {items.map(e => {
                        const meta = EARNING_STATUS[e.status];
                        return (
                          <tr key={e.id} className="border-b border-gray-100 dark:border-dracula-line/30 hover:bg-gray-50 dark:hover:bg-dracula-line/10 transition-colors">
                            <td className="px-4 py-2.5 text-gray-700 dark:text-dracula-fg font-medium">#{e.strategyId}</td>
                            <td className="px-4 py-2.5 text-gray-400 dark:text-dracula-comment tabular-nums">
                              {new Date(e.earnedAt).toLocaleDateString("ko-KR")}
                            </td>
                            <td className="px-4 py-2.5 text-right font-mono tabular-nums text-gray-700 dark:text-dracula-fg">{won(e.grossAmount)}</td>
                            <td className="px-4 py-2.5 text-right font-mono tabular-nums text-gray-400 dark:text-dracula-comment">-{won(e.platformFee)}</td>
                            <td className="px-4 py-2.5 text-right font-mono tabular-nums font-bold text-dracula-green">+{won(e.netAmount)}</td>
                            <td className="px-4 py-2.5">
                              <span className={`text-[10px] font-semibold ${meta.color}`}>{meta.label}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </Card>
                {(earningsData?.totalPages ?? 1) > 1 && (
                  <div className="flex justify-center gap-3 mt-5">
                    {earningPage > 0 && <button onClick={() => setEarningPage(p => p - 1)} className={btnCls}>이전</button>}
                    {earningPage < (earningsData?.totalPages ?? 1) - 1 && <button onClick={() => setEarningPage(p => p + 1)} className={btnCls}>다음</button>}
                  </div>
                )}
              </>
            );
          })()}

          {/* 출금 내역 — 테이블 */}
          {tab === "payouts" && (() => {
            const items: CreatorPayout[] = payoutsData?.content ?? [];
            return items.length === 0 ? (
              <div className="text-center py-20 text-sm text-gray-400 dark:text-dracula-comment border border-dashed border-gray-200 dark:border-dracula-line rounded-xl">
                출금 내역이 없습니다.
              </div>
            ) : (
              <>
                <Card className="overflow-hidden">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-gray-200 dark:border-dracula-line bg-gray-50/60 dark:bg-transparent text-gray-400 dark:text-dracula-comment">
                        <th className="px-4 py-2.5 text-left font-medium">금액</th>
                        <th className="px-4 py-2.5 text-left font-medium">계좌</th>
                        <th className="px-4 py-2.5 text-left font-medium">신청일</th>
                        <th className="px-4 py-2.5 text-left font-medium">처리일</th>
                        <th className="px-4 py-2.5 text-left font-medium">상태</th>
                      </tr>
                    </thead>
                    <tbody>
                      {items.map(p => {
                        const meta = PAYOUT_STATUS[p.status];
                        return (
                          <tr key={p.id} className="border-b border-gray-100 dark:border-dracula-line/30 hover:bg-gray-50 dark:hover:bg-dracula-line/10 transition-colors">
                            <td className="px-4 py-2.5 font-mono font-bold tabular-nums text-gray-900 dark:text-dracula-fg">{won(p.amount)}</td>
                            <td className="px-4 py-2.5 text-gray-500 dark:text-dracula-comment">
                              {p.bankName} {(p.accountNumber ?? "").slice(-4).padStart((p.accountNumber?.length ?? 0), "•")} ({p.accountHolder})
                            </td>
                            <td className="px-4 py-2.5 text-gray-400 dark:text-dracula-comment tabular-nums">
                              {new Date(p.requestedAt).toLocaleDateString("ko-KR")}
                            </td>
                            <td className="px-4 py-2.5 text-gray-400 dark:text-dracula-comment tabular-nums">
                              {p.processedAt ? new Date(p.processedAt).toLocaleDateString("ko-KR") : "—"}
                            </td>
                            <td className="px-4 py-2.5">
                              <span className={`text-[10px] font-semibold ${meta.color}`}>{meta.label}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </Card>
                {(payoutsData?.totalPages ?? 1) > 1 && (
                  <div className="flex justify-center gap-3 mt-5">
                    {payoutPage > 0 && <button onClick={() => setPayoutPage(p => p - 1)} className={btnCls}>이전</button>}
                    {payoutPage < (payoutsData?.totalPages ?? 1) - 1 && <button onClick={() => setPayoutPage(p => p + 1)} className={btnCls}>다음</button>}
                  </div>
                )}
              </>
            );
          })()}
        </main>
      </div>
    </div>
  );
}
